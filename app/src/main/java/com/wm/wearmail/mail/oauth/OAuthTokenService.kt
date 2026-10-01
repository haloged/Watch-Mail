package com.wm.wearmail.mail.oauth

import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.prefs.SettingsStore
import com.wm.wearmail.data.repo.AccountRepository
import com.wm.wearmail.mail.MailError
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.OAuthTokens

/**
 * OAuth2 令牌服务：**同步前保证 access token 可用**。
 *
 * 职责边界：
 * - [MicrosoftOAuth] 只负责协议（申请设备码、轮询、刷新），不碰数据库；
 * - 本类负责"什么时候该刷新、刷新后写到哪"，是协议层与仓储层之间的粘合层；
 * - [com.wm.wearmail.sync.SyncEngine] 在每次连接 IMAP/SMTP 前调用 [freshSecrets]，
 *   认证失败后再调用 [forceRefresh] 重试一次。
 *
 * 为什么必须有刷新：Microsoft 的 access token 只有约 1 小时有效期，
 * 若不刷新，用户会在"用了一段时间后突然同步失败"，且错误表现为认证失败，
 * 很难自诊断。
 */
class OAuthTokenService(
    private val accounts: AccountRepository,
    private val settings: SettingsStore,
    private val oauth: MicrosoftOAuth,
    /** 构建期默认客户端 ID（可在设置页覆盖，便于不重新打包就改） */
    private val buildConfigClientId: String = "",
) {

    /**
     * 生效的客户端 ID：**设置页填写的优先**，否则用构建期注入的值。
     *
     * 之所以允许运行时填写：Azure 应用注册要由用户自己完成（没人能替他注册），
     * 让他在手表上直接填 ID 比"改 gradle.properties 重新打包"现实得多。
     */
    fun configuredClientId(): String {
        val fromSettings = runCatching { settings.settings.value.microsoftOAuthClientId }.getOrDefault("")
        return fromSettings.trim().takeIf { it.isNotEmpty() }
            ?: buildConfigClientId.trim().takeIf { it.isNotEmpty() }
            ?: ""
    }

    /** 是否已配置客户端 ID（未配置时 UI 应引导去设置页） */
    fun isConfigured(): Boolean = configuredClientId().isNotEmpty()

    /** 生效的租户（默认 common：同时支持个人账号与企业账号） */
    fun currentTenant(): String = runCatching {
        MicrosoftOAuth.normalizeTenant(settings.settings.value.microsoftOAuthTenant)
    }.getOrDefault(MicrosoftOAuth.DEFAULT_TENANT)

    // ------------------------------------------------------------------
    // 同步前的凭据准备
    // ------------------------------------------------------------------

    /**
     * 读取凭据；若为 OAuth2 账户且 access token 即将过期，则先刷新并写回加密存储。
     */
    suspend fun freshSecrets(account: Account): Result<AccountSecrets> {
        val secrets = accounts.secrets(account.id)
            ?: return Result.failure(MailError.Config("账户凭据缺失或解密失败，请重新配置该账户"))

        if (account.authType != AuthType.OAUTH2) return Result.success(secrets)

        val tokens = secrets.oauth
            ?: return Result.failure(MailError.Auth("该账户尚未完成 OAuth2 授权，请在手表上重新授权"))

        if (!tokens.needsRefresh()) return Result.success(secrets)

        Logs.i(TAG, "access token 即将过期，先刷新（账户 id=${account.id}）")
        return refreshAndStore(account, tokens)
    }

    /**
     * 强制刷新（忽略过期判断）。用于认证失败后的重试：
     * 令牌可能已被服务器吊销或时钟偏差导致提前失效。
     */
    suspend fun forceRefresh(account: Account): Result<AccountSecrets> {
        val secrets = accounts.secrets(account.id)
            ?: return Result.failure(MailError.Config("账户凭据缺失或解密失败，请重新配置该账户"))
        val tokens = secrets.oauth
            ?: return Result.failure(MailError.Auth("该账户尚未完成 OAuth2 授权，请在手表上重新授权"))

        return refreshAndStore(account, tokens)
    }

    private suspend fun refreshAndStore(
        account: Account,
        tokens: OAuthTokens,
    ): Result<AccountSecrets> {
        val refreshToken = tokens.refreshToken
        if (refreshToken.isNullOrBlank()) {
            return Result.failure(MailError.Auth("授权已过期且没有刷新令牌，请在手表上重新授权"))
        }

        return oauth.refresh(refreshToken, configuredClientId(), currentTenant()).fold(
            onSuccess = { fresh ->
                // 写回加密存储：下次启动/下次同步直接可用
                val ok = runCatching {
                    accounts.update(account, AccountSecrets(oauth = fresh))
                }.getOrDefault(false)
                if (!ok) {
                    Logs.w(TAG, "刷新后的令牌写回失败（账户 id=${account.id}），本次仍按新令牌继续")
                }
                Result.success(AccountSecrets(oauth = fresh))
            },
            onFailure = { error ->
                Logs.w(TAG, "刷新 OAuth2 令牌失败：${error.toMailError().javaClass.simpleName}")
                Result.failure(error.toMailError())
            },
        )
    }

    // ------------------------------------------------------------------
    // 设备码授权（UI 分两步调用：先拿码展示，再等待授权）
    // ------------------------------------------------------------------

    /** 申请设备码：UI 拿到后展示短码 + 授权网址（可渲染成二维码） */
    suspend fun requestDeviceCode(): Result<DeviceCodeInfo> {
        val clientId = configuredClientId()
        if (clientId.isEmpty()) return Result.failure(MailError.Config(MSG_NO_CLIENT_ID))
        return oauth.requestDeviceCode(clientId, currentTenant())
    }

    /** 等待用户在浏览器完成授权 */
    suspend fun awaitAuthorization(
        info: DeviceCodeInfo,
        onStatus: (DeviceCodeStatus) -> Unit = {},
    ): Result<OAuthTokens> {
        val clientId = configuredClientId()
        if (clientId.isEmpty()) return Result.failure(MailError.Config(MSG_NO_CLIENT_ID))
        return oauth.awaitAuthorization(info, clientId, currentTenant(), onStatus = onStatus)
    }

    /** 一步完成授权（申请设备码 → 等待），供不需要展示短码的场景使用 */
    suspend fun authorizeDeviceFlow(
        onStatus: (DeviceCodeStatus) -> Unit = {},
    ): Result<OAuthTokens> = requestDeviceCode().fold(
        onSuccess = { info -> awaitAuthorization(info, onStatus) },
        onFailure = { Result.failure(it) },
    )

    companion object {
        private const val TAG = "OAuthTokens"

        /** 未配置客户端 ID 时的提示（UI 直接展示，因此写成完整可执行的指引） */
        const val MSG_NO_CLIENT_ID: String =
            "尚未配置 Microsoft OAuth2 客户端 ID：请在「设置 → Outlook OAuth2」中填写，或在 gradle.properties 里设置 wearmail.microsoftOAuthClientId"
    }
}

/** 复用一个内部的错误归一化（避免与 MailError 的扩展重复定义） */
private fun Throwable.toMailError(): MailError = when (this) {
    is MailError -> this
    else -> MailError.Unknown(message ?: "OAuth2 请求失败")
}
