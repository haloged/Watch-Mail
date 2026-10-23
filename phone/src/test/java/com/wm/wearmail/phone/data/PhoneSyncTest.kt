package com.wm.wearmail.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端同步逻辑的单元测试（纯函数）。
 *
 * 守护点：手表二维码解析（host/port/token）、配置 JSON 解析
 * （OAuth2 与空密码账户被跳过）、加密前存储 JSON 的往返一致性。
 */
class PhoneSyncTest {

    // ---------------- PairingUrl ----------------

    @Test
    fun `解析标准二维码地址`() {
        val endpoint = PairingUrl.parse("http://192.168.1.5:8080/?t=abc123")
        assertEquals("192.168.1.5", endpoint?.host)
        assertEquals(8080, endpoint?.port)
        assertEquals("abc123", endpoint?.token)
        assertEquals("http://192.168.1.5:8080/export?t=abc123", endpoint?.exportUrl)
    }

    @Test
    fun `忽略未带 token 或非 http 的内容`() {
        assertNull(PairingUrl.parse("http://192.168.1.5:8080/"))
        assertNull(PairingUrl.parse("ftp://192.168.1.5/?t=abc"))
        assertNull(PairingUrl.parse(""))
        assertNull(PairingUrl.parse("not a url"))
    }

    @Test
    fun `只接受局域网私有地址`() {
        assertNotNull(PairingUrl.parse("http://192.168.1.5:8080/?t=abc"))
        assertNotNull(PairingUrl.parse("http://10.0.0.8:8080/?t=abc"))
        assertNotNull(PairingUrl.parse("http://172.20.1.2:8080/?t=abc"))
        // 公网二维码（如下载页）不是同步码；伪装成私有前缀的域名也不行
        assertNull(PairingUrl.parse("https://github.com/haloged/Watch-Mail/releases"))
        assertNull(PairingUrl.parse("https://example.com/x?t=abc"))
        assertNull(PairingUrl.parse("http://192.168.1.5.evil.com/?t=abc"))
    }

    // ---------------- SyncedAccount.parseConfig ----------------

    private val watchJson = """
        {
          "version": 1,
          "accounts": [
            {"email":"a@qq.com","alias":"工作","authType":"APP_PASSWORD",
             "imapHost":"imap.qq.com","imapPort":993,"imapSecurity":"SSL_TLS",
             "smtpHost":"smtp.qq.com","smtpPort":465,"smtpSecurity":"SSL_TLS",
             "password":"授权码"},
            {"email":"x@outlook.com","authType":"OAUTH2",
             "imapHost":"outlook.office365.com","imapPort":993,
             "oauthAccessToken":"access","oauthRefreshToken":"refresh"},
            {"email":"nopwd@gmail.com","authType":"APP_PASSWORD",
             "imapHost":"imap.gmail.com","imapPort":993}
          ]
        }
    """.trimIndent()

    @Test
    fun `解析配置同时支持密码与 OAuth2 账户`() {
        val accounts = SyncedAccount.parseConfig(watchJson)!!
        // 密码账户 + OAuth2 账户；无凭据的 nopwd@gmail.com 被跳过
        assertEquals(2, accounts.size)

        val pwd = accounts.first { it.email == "a@qq.com" }
        assertEquals("工作", pwd.alias)
        assertEquals("imap.qq.com", pwd.imapHost)
        assertEquals(993, pwd.imapPort)
        assertEquals("授权码", pwd.password)

        val oauth = accounts.first { it.email == "x@outlook.com" }
        assertEquals("access", oauth.oauth?.accessToken)
        assertEquals("refresh", oauth.oauth?.refreshToken)
        assertEquals("", oauth.password)

        assertTrue(accounts.none { it.email == "nopwd@gmail.com" })
    }

    @Test
    fun `OAuth2 账户存储往返保留令牌组`() {
        val original = listOf(
            SyncedAccount(
                email = "x@outlook.com",
                authType = com.wm.wearmail.model.AuthType.OAUTH2,
                imapHost = "outlook.office365.com",
                oauth = com.wm.wearmail.model.OAuthTokens(
                    accessToken = "access",
                    refreshToken = "refresh",
                    expiresAtMillis = 123L,
                    scope = "scope",
                ),
            ),
        )
        val parsed = SyncedAccount.parseConfig(SyncedAccount.toJson(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `非法或版本不符的配置返回 null`() {
        assertNull(SyncedAccount.parseConfig("not json"))
        assertNull(SyncedAccount.parseConfig("""{"version":2,"accounts":[]}"""))
        assertNull(SyncedAccount.parseConfig("""{"accounts":[]}"""))
        // 合法但过滤后为空 → 空列表（不是 null）
        assertEquals(0, SyncedAccount.parseConfig("""{"version":1,"accounts":[]}""")?.size)
    }

    @Test
    fun `存储 JSON 往返一致`() {
        val original = listOf(
            SyncedAccount(
                email = "a@qq.com",
                alias = "工作",
                imapHost = "imap.qq.com",
                imapPort = 993,
                password = "授权码",
            ),
        )
        val parsed = SyncedAccount.parseConfig(SyncedAccount.toJson(original))
        assertEquals(original, parsed)
        assertTrue(parsed!!.isNotEmpty())
    }
}
