package com.wm.wearmail.mail.oauth

import com.wm.wearmail.mail.MailError
import com.wm.wearmail.model.OAuthTokens
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Microsoft OAuth2 客户端的单元测试。
 *
 * 覆盖范围：
 * - 端点地址与请求表单（对照微软文档：IMAP/SMTP 的完整资源 scope、设备码 grant type）；
 * - `/devicecode` 与 `/token` 响应解析（含设备码流的四种"正常错误"）；
 * - 轮询状态机：pending / slow_down / 拒绝 / 过期 / 网络抖动；
 * - 刷新令牌（响应不含新 refresh token 时沿用旧的）。
 *
 * 网络层用假 [FormPoster]，时钟与休眠都由测试控制，因此**不需要真实网络也不需要等待**。
 */
class MicrosoftOAuthTest {

    // ---------------- 测试替身 ----------------

    private class FakeClock(var millis: Long = 1_700_000_000_000L) {
        fun now(): Long = millis
    }

    private class FakePoster(responses: List<Result<String>>) : FormPoster {
        private val queue = ArrayDeque(responses)
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun post(
            url: String,
            form: Map<String, String>,
            timeoutMillis: Int,
        ): Result<String> {
            requests += url to form
            return queue.removeFirstOrNull() ?: Result.failure(IOException("没有更多预设响应"))
        }
    }

    private fun deviceInfo(clock: FakeClock, validSeconds: Long = 900L) = DeviceCodeInfo(
        deviceCode = "device-code",
        userCode = "ABCD-EFGH",
        verificationUri = "https://microsoft.com/devicelogin",
        expiresAtMillis = clock.now() + validSeconds * 1000L,
        intervalMillis = 5_000L,
    )

    // ---------------- 端点与请求表单 ----------------

    @Test
    fun `租户为空或含空白时回退 common`() {
        assertEquals("common", MicrosoftOAuth.normalizeTenant(null))
        assertEquals("common", MicrosoftOAuth.normalizeTenant(""))
        assertEquals("common", MicrosoftOAuth.normalizeTenant("   "))
        assertEquals("contoso.onmicrosoft.com", MicrosoftOAuth.normalizeTenant("  contoso.onmicrosoft.com "))
        assertEquals("organizations", MicrosoftOAuth.normalizeTenant("organizations"))
    }

    @Test
    fun `端点地址与微软文档一致`() {
        assertEquals(
            "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
            MicrosoftOAuth.deviceCodeUrl(),
        )
        assertEquals(
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            MicrosoftOAuth.tokenUrl(),
        )
        assertEquals(
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
            MicrosoftOAuth.deviceCodeUrl("consumers"),
        )
    }

    @Test
    fun `设备码请求带客户端 ID 与 IMAP SMTP 离线三个 scope`() {
        val form = MicrosoftOAuth.buildDeviceCodeForm("client-123")

        assertEquals("client-123", form["client_id"])
        val scope = form.getValue("scope")
        assertTrue(scope.contains("offline_access"))
        assertTrue(scope.contains("https://outlook.office.com/IMAP.AccessAsUser.All"))
        assertTrue(scope.contains("https://outlook.office.com/SMTP.Send"))
        // scope 之间用空格分隔（OAuth2 规范）
        assertEquals(3, scope.split(" ").size)
    }

    @Test
    fun `轮询请求使用设备码受权类型`() {
        val form = MicrosoftOAuth.buildPollForm("client-123", "dev-code")

        assertEquals("urn:ietf:params:oauth:grant-type:device_code", form["grant_type"])
        assertEquals("client-123", form["client_id"])
        assertEquals("dev-code", form["device_code"])
    }

    @Test
    fun `刷新请求使用 refresh_token 类型并回传 scope`() {
        val form = MicrosoftOAuth.buildRefreshForm("client-123", "refresh-token")

        assertEquals("refresh_token", form["grant_type"])
        assertEquals("client-123", form["client_id"])
        assertEquals("refresh-token", form["refresh_token"])
        assertTrue(form.getValue("scope").contains("offline_access"))
    }

    // ---------------- /devicecode 解析 ----------------

    @Test
    fun `解析设备码响应`() {
        val json = """
            {"user_code":"ABCD-EFGH","device_code":"dev-code",
             "verification_uri":"https://microsoft.com/devicelogin",
             "expires_in":900,"interval":5,"message":"请访问网址并输入代码"}
        """.trimIndent()

        val info = MicrosoftOAuth.parseDeviceCode(json, 1_000L)

        assertNotNull(info)
        assertEquals("ABCD-EFGH", info!!.userCode)
        assertEquals("dev-code", info.deviceCode)
        assertEquals("https://microsoft.com/devicelogin", info.verificationUri)
        assertEquals(1_000L + 900_000L, info.expiresAtMillis)
        assertEquals(5_000L, info.intervalMillis)
        assertEquals("请访问网址并输入代码", info.message)
    }

    @Test
    fun `设备码响应缺 interval 时用默认值`() {
        val json = """{"user_code":"A","device_code":"d","verification_uri":"https://x"}"""

        val info = MicrosoftOAuth.parseDeviceCode(json, 0L)

        assertEquals(5_000L, info?.intervalMillis)
        assertEquals(900_000L, info?.expiresAtMillis)
    }

    @Test
    fun `设备码响应错误或字段缺失时返回 null`() {
        assertNull(MicrosoftOAuth.parseDeviceCode("""{"error":"invalid_client"}""", 0L))
        assertNull(MicrosoftOAuth.parseDeviceCode("""{"user_code":"X"}""", 0L))
        assertNull(MicrosoftOAuth.parseDeviceCode("""{"user_code":"","device_code":"","verification_uri":""}""", 0L))
        assertNull(MicrosoftOAuth.parseDeviceCode("不是 JSON", 0L))
    }

    // ---------------- /token 解析 ----------------

    @Test
    fun `解析授权成功响应`() {
        val json = """
            {"token_type":"Bearer","scope":"offline_access IMAP.AccessAsUser.All",
             "expires_in":3600,"access_token":"access-1","refresh_token":"refresh-1"}
        """.trimIndent()

        val result = MicrosoftOAuth.parseTokenPoll(json, 5_000L)

        assertTrue(result is TokenPollResult.Authorized)
        val tokens = (result as TokenPollResult.Authorized).tokens
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(5_000L + 3_600_000L, tokens.expiresAtMillis)
    }

    @Test
    fun `设备码流的四种预期错误分别映射`() {
        assertTrue(MicrosoftOAuth.parseTokenPoll("""{"error":"authorization_pending"}""", 0L) is TokenPollResult.Pending)
        assertTrue(MicrosoftOAuth.parseTokenPoll("""{"error":"slow_down"}""", 0L) is TokenPollResult.SlowDown)
        assertTrue(MicrosoftOAuth.parseTokenPoll("""{"error":"authorization_declined"}""", 0L) is TokenPollResult.Declined)
        assertTrue(MicrosoftOAuth.parseTokenPoll("""{"error":"expired_token"}""", 0L) is TokenPollResult.Expired)
        assertTrue(MicrosoftOAuth.parseTokenPoll("""{"error":"bad_verification_code"}""", 0L) is TokenPollResult.Expired)
    }

    @Test
    fun `其它错误保留微软返回的描述`() {
        val json = """{"error":"invalid_client","error_description":"AADSTS7000218: 请求体必须包含 client_secret"}"""

        val result = MicrosoftOAuth.parseTokenPoll(json, 0L)

        assertTrue(result is TokenPollResult.Failed)
        assertTrue((result as TokenPollResult.Failed).message.contains("AADSTS7000218"))
    }

    @Test
    fun `缺少 access token 视为失败`() {
        val result = MicrosoftOAuth.parseTokenPoll("""{"token_type":"Bearer","expires_in":3600}""", 0L)

        assertTrue(result is TokenPollResult.Failed)
    }

    // ---------------- 轮询状态机 ----------------

    @Test
    fun `轮询：先 pending 后授权成功`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(
                Result.success("""{"error":"authorization_pending"}"""),
                Result.success("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""),
            ),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)
        val statuses = mutableListOf<DeviceCodeStatus>()

        val result = oauth.awaitAuthorization(
            info = deviceInfo(clock),
            clientId = "client-123",
            sleeper = { clock.millis += it },
            onStatus = { statuses += it },
        )

        assertTrue(result.isSuccess)
        assertEquals("at", result.getOrNull()?.accessToken)
        assertEquals(2, poster.requests.size)
        assertTrue(statuses.first() is DeviceCodeStatus.Waiting)
        assertTrue(statuses.last() is DeviceCodeStatus.Authorized)
    }

    @Test
    fun `轮询：slow_down 会把间隔加 5 秒`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(
                Result.success("""{"error":"slow_down"}"""),
                Result.success("""{"access_token":"at","expires_in":3600}"""),
            ),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)
        val sleeps = mutableListOf<Long>()

        val result = oauth.awaitAuthorization(
            info = deviceInfo(clock),
            clientId = "client-123",
            sleeper = { sleeps += it; clock.millis += it },
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(10_000L), sleeps)
    }

    @Test
    fun `轮询：用户拒绝授权立即结束`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(listOf(Result.success("""{"error":"authorization_declined"}""")))
        val oauth = MicrosoftOAuth(poster, clock::now)
        val statuses = mutableListOf<DeviceCodeStatus>()

        val result = oauth.awaitAuthorization(
            info = deviceInfo(clock),
            clientId = "client-123",
            sleeper = { clock.millis += it },
            onStatus = { statuses += it },
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MailError.Auth)
        assertTrue(statuses.last() is DeviceCodeStatus.Declined)
        assertEquals("只应请求一次", 1, poster.requests.size)
    }

    @Test
    fun `轮询：设备码过期时结束`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(
                Result.success("""{"error":"authorization_pending"}"""),
                Result.success("""{"error":"authorization_pending"}"""),
            ),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)
        val statuses = mutableListOf<DeviceCodeStatus>()

        // 设备码只剩 3 秒，而轮询间隔 5 秒 —— 第一次等待后即过期
        val result = oauth.awaitAuthorization(
            info = deviceInfo(clock, validSeconds = 3L),
            clientId = "client-123",
            sleeper = { clock.millis += it },
            onStatus = { statuses += it },
        )

        assertTrue(result.isFailure)
        assertTrue(statuses.last() is DeviceCodeStatus.Expired)
    }

    @Test
    fun `轮询：网络抖动可恢复`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(
                Result.failure(IOException("连接被重置")),
                Result.failure(IOException("连接被重置")),
                Result.success("""{"access_token":"at","expires_in":3600}"""),
            ),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)

        val result = oauth.awaitAuthorization(
            info = deviceInfo(clock),
            clientId = "client-123",
            sleeper = { clock.millis += it },
        )

        assertTrue("偶发网络失败不应中断授权", result.isSuccess)
        assertEquals(3, poster.requests.size)
    }

    @Test
    fun `轮询：连续网络失败超过上限才放弃`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            List(4) { Result.failure(IOException("网络不可达")) },
        )
        val oauth = MicrosoftOAuth(poster, clock::now)
        val statuses = mutableListOf<DeviceCodeStatus>()

        val result = oauth.awaitAuthorization(
            info = deviceInfo(clock),
            clientId = "client-123",
            sleeper = { clock.millis += it },
            onStatus = { statuses += it },
        )

        assertTrue(result.isFailure)
        assertTrue(statuses.last() is DeviceCodeStatus.Failed)
        assertEquals(4, poster.requests.size)
    }

    @Test
    fun `未配置客户端 ID 时不发请求`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(emptyList())
        val oauth = MicrosoftOAuth(poster, clock::now)

        val deviceResult = oauth.requestDeviceCode(clientId = "")
        val pollResult = oauth.awaitAuthorization(deviceInfo(clock), clientId = "")

        assertTrue(deviceResult.isFailure)
        assertTrue(deviceResult.exceptionOrNull() is MailError.Config)
        assertTrue(pollResult.isFailure)
        assertEquals(0, poster.requests.size)
    }

    // ---------------- 刷新令牌 ----------------

    @Test
    fun `刷新成功且响应未返回新 refresh token 时沿用旧的`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(Result.success("""{"access_token":"new-access","expires_in":3600}""")),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)

        val result = oauth.refresh("old-refresh", "client-123")

        assertTrue(result.isSuccess)
        val tokens = result.getOrNull()!!
        assertEquals("new-access", tokens.accessToken)
        assertEquals("旧刷新令牌必须保留，否则下次无法续期", "old-refresh", tokens.refreshToken)
        // 刷新请求本身必须带 grant_type=refresh_token
        assertEquals("refresh_token", poster.requests.single().second["grant_type"])
    }

    @Test
    fun `刷新响应带新 refresh token 时滚动更新`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(Result.success("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)

        val tokens = oauth.refresh("old-refresh", "client-123").getOrNull()

        assertEquals("new-refresh", tokens?.refreshToken)
    }

    @Test
    fun `刷新被拒绝时返回不可重试的认证错误`() = runTest {
        val clock = FakeClock()
        val poster = FakePoster(
            listOf(Result.success("""{"error":"invalid_grant","error_description":"refresh token 已过期"}""")),
        )
        val oauth = MicrosoftOAuth(poster, clock::now)

        val result = oauth.refresh("old-refresh", "client-123")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("应归为认证错误", error is MailError.Auth)
        assertFalse("认证错误不应自动重试", (error as MailError).isRetryable)
    }

    @Test
    fun `缺少客户端 ID 或刷新令牌时直接失败`() = runTest {
        val oauth = MicrosoftOAuth(FakePoster(emptyList())) { 0L }

        assertTrue(oauth.refresh("rt", clientId = "").exceptionOrNull() is MailError.Config)
        assertTrue(oauth.refresh(refreshToken = "", clientId = "cid").exceptionOrNull() is MailError.Auth)
    }

    @Test
    fun `令牌模型可序列化后写库再读回`() {
        val tokens = OAuthTokens("at", "rt", 123L, "offline_access")

        assertEquals(tokens, OAuthTokens.parse(tokens.serialize()))
    }
}
