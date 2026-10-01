package com.wm.wearmail.mail.oauth

import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.MailError
import com.wm.wearmail.model.OAuthTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * 设备码信息（`/devicecode` 端点的响应）。
 *
 * @param verificationUri 用户在手机/电脑浏览器打开的授权页（微软当前**不支持**
 *   `verification_uri_complete`，因此二维码里只能放这个网址，用户仍需手输 [userCode]）
 * @param userCode 短码，用户在授权页输入
 * @param expiresAtMillis 设备码过期时刻：从此刻起用户有约 15 分钟完成登录
 * @param intervalMillis 轮询间隔（服务器建议值）
 */
data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalMillis: Long,
    val message: String? = null,
)

/**
 * 令牌端点轮询结果。
 *
 * 错误分支严格对应微软文档列出的四种预期错误（设备码流是轮询协议，
 * 这些"错误"是正常流程的一部分）：
 * `authorization_pending` / `slow_down` / `authorization_declined` / `expired_token`。
 */
sealed interface TokenPollResult {
    data class Authorized(val tokens: OAuthTokens) : TokenPollResult

    /** 用户尚未完成授权：按 interval 继续轮询 */
    data object Pending : TokenPollResult

    /** 轮询过快：按 RFC 8628 把间隔 +5 秒后继续 */
    data object SlowDown : TokenPollResult

    /** 用户拒绝了授权：停止轮询 */
    data object Declined : TokenPollResult

    /** 设备码过期或无效：停止轮询，需要重新获取 */
    data object Expired : TokenPollResult

    /** 其它失败（含 invalid_client 等配置问题） */
    data class Failed(val message: String) : TokenPollResult
}

/** 授权进度（供 UI 展示；不包含任何令牌内容） */
sealed interface DeviceCodeStatus {
    data class Waiting(val secondsLeft: Long) : DeviceCodeStatus
    data class SlowDown(val intervalMillis: Long) : DeviceCodeStatus
    data object Authorized : DeviceCodeStatus
    data object Declined : DeviceCodeStatus
    data object Expired : DeviceCodeStatus
    data class Failed(val message: String) : DeviceCodeStatus
}

/**
 * Microsoft（Outlook / Office 365 / Hotmail）OAuth2 客户端。
 *
 * 为什么用手表端用**设备代码流**（device authorization grant）而不是授权码流：
 * 手表屏幕只有 466px、没有可用的浏览器控件，而设备代码流正是为
 * 「电视/打印机/物联网设备」这类输入受限设备设计的 —— 手表只负责显示短码，
 * 用户在手机浏览器完成登录，手表轮询取令牌。配合本项目已有的二维码渲染，
 * 用户扫码即可打开授权页（仍需手输短码，微软暂不支持预填）。
 *
 * 关键事实（依据微软官方文档）：
 * - IMAP 需要 scope `https://outlook.office.com/IMAP.AccessAsUser.All`；
 * - SMTP AUTH 需要 scope `https://outlook.office.com/SMTP.Send`；
 * - 追加 `offline_access` 才会返回 refresh token；
 * - 租户用 `/common` 可同时支持个人账号与企业账号；
 * - 传输层用 SASL XOAUTH2，JavaMail 通过
 *   `mail.imap.auth.mechanisms=XOAUTH2` + 「access token 当作密码」实现
 *   （用户名必须是邮箱地址）。
 *
 * 本类不感知 UI 与数据库：进度通过 [onStatus] 回调，令牌由调用方保存。
 */
class MicrosoftOAuth(
    private val poster: FormPoster = HttpFormPoster(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 申请设备码与用户短码。
     *
     * @param clientId Azure 应用（公共客户端）的 Application ID
     * @param tenant 租户：`common` / `organizations` / `consumers` / 具体租户 ID
     */
    suspend fun requestDeviceCode(
        clientId: String,
        tenant: String = DEFAULT_TENANT,
    ): Result<DeviceCodeInfo> {
        if (clientId.isBlank()) {
            return Result.failure(MailError.Config("尚未配置 Microsoft OAuth2 客户端 ID"))
        }
        return poster.post(deviceCodeUrl(tenant), buildDeviceCodeForm(clientId)).fold(
            onSuccess = { body ->
                val info = parseDeviceCode(body, now())
                if (info == null) {
                    Result.failure(MailError.Protocol("设备码响应无法解析，请稍后重试"))
                } else {
                    Logs.i(TAG, "已获取设备码：验证地址=${info.verificationUri}，有效期=${(info.expiresAtMillis - now()) / 1000}s")
                    Result.success(info)
                }
            },
            onFailure = { Result.failure(it.toOAuthError("申请设备码失败")) },
        )
    }

    /**
     * 轮询令牌端点直到用户完成授权、拒绝或设备码过期。
     *
     * @param sleeper 轮询等待（测试注入假实现即可瞬间跑完全部分支）
     * @param onStatus 进度回调（UI 用；只报告状态，不泄露令牌）
     */
    suspend fun awaitAuthorization(
        info: DeviceCodeInfo,
        clientId: String,
        tenant: String = DEFAULT_TENANT,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        onStatus: (DeviceCodeStatus) -> Unit = {},
    ): Result<OAuthTokens> {
        if (clientId.isBlank()) {
            return Result.failure(MailError.Config("尚未配置 Microsoft OAuth2 客户端 ID"))
        }

        val url = tokenUrl(tenant)
        val form = buildPollForm(clientId, info.deviceCode)
        var interval = info.intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
        var networkErrors = 0

        while (true) {
            coroutineContext.ensureActive()

            val remaining = info.expiresAtMillis - now()
            if (remaining <= 0L) {
                onStatus(DeviceCodeStatus.Expired)
                return Result.failure(MailError.Auth("设备码已过期，请在手表上重新获取"))
            }

            val outcome = poster.post(url, form).fold(
                onSuccess = { body ->
                    networkErrors = 0
                    parseTokenPoll(body, now())
                },
                onFailure = { error ->
                    // 手表 Wi-Fi 可能抖动：连续失败超过上限才放弃，否则继续轮询
                    networkErrors += 1
                    if (networkErrors > MAX_CONSECUTIVE_NETWORK_ERRORS) {
                        TokenPollResult.Failed(error.message ?: "网络请求失败")
                    } else {
                        TokenPollResult.Pending
                    }
                },
            )

            when (outcome) {
                is TokenPollResult.Authorized -> {
                    onStatus(DeviceCodeStatus.Authorized)
                    Logs.i(TAG, "OAuth2 授权成功：有效期 ${(outcome.tokens.expiresAtMillis - now()) / 1000}s，可刷新=${outcome.tokens.canRefresh}")
                    return Result.success(outcome.tokens)
                }

                TokenPollResult.Pending -> {
                    onStatus(DeviceCodeStatus.Waiting(remaining / 1000L))
                    sleeper(interval)
                }

                TokenPollResult.SlowDown -> {
                    interval += SLOW_DOWN_STEP_MILLIS
                    Logs.w(TAG, "服务器要求降低轮询频率，间隔调整为 ${interval}ms")
                    onStatus(DeviceCodeStatus.SlowDown(interval))
                    sleeper(interval)
                }

                TokenPollResult.Declined -> {
                    onStatus(DeviceCodeStatus.Declined)
                    return Result.failure(MailError.Auth("已在浏览器中拒绝授权"))
                }

                TokenPollResult.Expired -> {
                    onStatus(DeviceCodeStatus.Expired)
                    return Result.failure(MailError.Auth("设备码已失效，请在手表上重新获取"))
                }

                is TokenPollResult.Failed -> {
                    onStatus(DeviceCodeStatus.Failed(outcome.message))
                    return Result.failure(MailError.Auth(outcome.message))
                }
            }
        }
    }

    /**
     * 用 refresh token 换取新的 access token。
     *
     * 刷新响应通常**不再返回** refresh token，此时沿用旧的（微软的滚动刷新策略）。
     */
    suspend fun refresh(
        refreshToken: String,
        clientId: String,
        tenant: String = DEFAULT_TENANT,
    ): Result<OAuthTokens> {
        if (clientId.isBlank()) {
            return Result.failure(MailError.Config("尚未配置 Microsoft OAuth2 客户端 ID"))
        }
        if (refreshToken.isBlank()) {
            return Result.failure(MailError.Auth("缺少刷新令牌，需要重新授权"))
        }

        return poster.post(tokenUrl(tenant), buildRefreshForm(clientId, refreshToken)).fold(
            onSuccess = { body ->
                when (val parsed = parseTokenPoll(body, now())) {
                    is TokenPollResult.Authorized -> {
                        val merged = parsed.tokens.copy(
                            refreshToken = parsed.tokens.refreshToken ?: refreshToken,
                        )
                        Logs.i(TAG, "OAuth2 令牌已刷新：有效期 ${(merged.expiresAtMillis - now()) / 1000}s")
                        Result.success(merged)
                    }
                    // 刷新只可能因授权失效/客户端配置错误而失败，统一归为不可重试的认证错误
                    is TokenPollResult.Failed -> Result.failure(MailError.Auth(parsed.message))
                    else -> Result.failure(MailError.Auth("刷新令牌被拒绝，需要重新授权"))
                }
            },
            onFailure = { Result.failure(it.toOAuthError("刷新令牌失败")) },
        )
    }

    companion object {
        private const val TAG = "MicrosoftOAuth"

        /** 授权与令牌端点主机 */
        const val AUTHORITY: String = "https://login.microsoftonline.com"

        /** 默认租户：`common` 同时支持个人账号（outlook.com/hotmail）与企业账号 */
        const val DEFAULT_TENANT: String = "common"

        /** 设备码流的 grant_type */
        const val DEVICE_CODE_GRANT: String = "urn:ietf:params:oauth:grant-type:device_code"

        /** IMAP + SMTP + 离线刷新所需的全部 scope（顺序无关） */
        val IMAP_SMTP_SCOPES: List<String> = listOf(
            "offline_access",
            "https://outlook.office.com/IMAP.AccessAsUser.All",
            "https://outlook.office.com/SMTP.Send",
        )

        /** 服务器未给出 interval 时的最小轮询间隔 */
        private const val MIN_INTERVAL_MILLIS: Long = 5_000L

        /** RFC 8628：收到 slow_down 时轮询间隔增加 5 秒 */
        private const val SLOW_DOWN_STEP_MILLIS: Long = 5_000L

        /** 连续网络失败上限（超过即放弃，避免手表一直空转耗电） */
        private const val MAX_CONSECUTIVE_NETWORK_ERRORS: Int = 3

        // ---------------- URL 与表单（纯函数，可单测） ----------------

        /** 规范化租户：空值回退到 [DEFAULT_TENANT] */
        fun normalizeTenant(raw: String?): String =
            raw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TENANT

        fun deviceCodeUrl(tenant: String = DEFAULT_TENANT): String =
            "$AUTHORITY/${normalizeTenant(tenant)}/oauth2/v2.0/devicecode"

        fun tokenUrl(tenant: String = DEFAULT_TENANT): String =
            "$AUTHORITY/${normalizeTenant(tenant)}/oauth2/v2.0/token"

        fun buildDeviceCodeForm(
            clientId: String,
            scopes: List<String> = IMAP_SMTP_SCOPES,
        ): Map<String, String> = mapOf(
            "client_id" to clientId,
            "scope" to scopes.joinToString(" "),
        )

        fun buildPollForm(clientId: String, deviceCode: String): Map<String, String> = mapOf(
            "grant_type" to DEVICE_CODE_GRANT,
            "client_id" to clientId,
            "device_code" to deviceCode,
        )

        fun buildRefreshForm(
            clientId: String,
            refreshToken: String,
            scopes: List<String> = IMAP_SMTP_SCOPES,
        ): Map<String, String> = mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "scope" to scopes.joinToString(" "),
        )

        // ---------------- 响应解析（纯函数，可单测） ----------------

        /**
         * 解析 `/devicecode` 响应。
         *
         * @param nowMillis 当前时间（显式传入以便测试确定化）
         * @return 字段缺失或返回 error 时 null
         */
        fun parseDeviceCode(json: String, nowMillis: Long): DeviceCodeInfo? = runCatching {
            val obj = JSONObject(json)
            if (obj.has("error")) return@runCatching null

            val deviceCode = obj.optString("device_code")
            val userCode = obj.optString("user_code")
            val verificationUri = obj.optString("verification_uri")
            if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
                return@runCatching null
            }

            DeviceCodeInfo(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                expiresAtMillis = nowMillis + obj.optInt("expires_in", DEFAULT_DEVICE_CODE_SECONDS) * 1000L,
                intervalMillis = obj.optInt("interval", DEFAULT_POLL_INTERVAL_SECONDS).coerceAtLeast(1) * 1000L,
                message = obj.optString("message").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        /** 解析令牌端点响应（设备码轮询与刷新共用同一响应结构） */
        fun parseTokenPoll(json: String, nowMillis: Long): TokenPollResult = runCatching {
            val obj = JSONObject(json)

            val error = obj.optString("error").takeIf { it.isNotBlank() }
            if (error != null) {
                return@runCatching when (error) {
                    "authorization_pending" -> TokenPollResult.Pending
                    "slow_down" -> TokenPollResult.SlowDown
                    "authorization_declined" -> TokenPollResult.Declined
                    "expired_token", "bad_verification_code" -> TokenPollResult.Expired
                    else -> TokenPollResult.Failed(
                        obj.optString("error_description").takeIf { it.isNotBlank() }
                            ?: "Microsoft 返回错误：$error",
                    )
                }
            }

            val accessToken = obj.optString("access_token")
            if (accessToken.isBlank()) {
                return@runCatching TokenPollResult.Failed("令牌响应缺少 access_token")
            }

            TokenPollResult.Authorized(
                OAuthTokens(
                    accessToken = accessToken,
                    refreshToken = obj.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresAtMillis = nowMillis + obj.optInt("expires_in", DEFAULT_ACCESS_TOKEN_SECONDS) * 1000L,
                    scope = obj.optString("scope").takeIf { it.isNotBlank() },
                ),
            )
        }.getOrElse { error ->
            TokenPollResult.Failed("令牌响应解析失败：${error.javaClass.simpleName}")
        }

        private const val DEFAULT_DEVICE_CODE_SECONDS = 900
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 5
        private const val DEFAULT_ACCESS_TOKEN_SECONDS = 3600
    }
}

/** 把网络异常翻译成对用户友好的 [MailError]（不泄露请求内容） */
private fun Throwable.toOAuthError(defaultMessage: String): MailError = when (this) {
    is MailError -> this
    is java.net.UnknownHostException -> MailError.Network("无法连接 Microsoft 登录服务，请检查网络")
    is java.net.SocketTimeoutException -> MailError.Timeout("连接 Microsoft 登录服务超时")
    is java.io.IOException -> MailError.Network(message ?: defaultMessage)
    else -> MailError.Unknown(message ?: defaultMessage)
}
