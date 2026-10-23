package com.wm.wearmail.pairing

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import org.json.JSONArray
import org.json.JSONObject

/** 导出给手机端的一个账户（配置 + 解密后的凭据） */
data class ExportAccount(
    val account: Account,
    val secrets: AccountSecrets,
)

/**
 * 「同步到手机」的配置导出（纯函数，可单元测试）。
 *
 * 背景：OPPO 等手表系统限制了第三方应用的手表端通知，但会**自动同步手机通知**。
 * 因此配套手机应用扫码拉取手表上的账户配置后，由手机收信并发出手机通知，
 * 通知即可经手表同步到达（见 docs/TECHNICAL.md「手机配套应用」）。
 *
 * 安全边界（务必遵守）：
 * - 导出内容含**明文凭据**（密码/授权码；OAuth2 的令牌组）——手表端存的是
 *   Keystore 密文，手机端无法解密，只能由手表解密后经**局域网**传给手机，
 *   手机再用自己的 Keystore 重新加密落盘；
 * - 该端点只在用户主动打开的「同步到手机」会话内可达，且必须携带一次性 token
 *   （与网页配对同等级别的保护）；
 * - 导出内容**绝不**写日志。
 */
object PairingExport {

    /** 协议版本：手机端据此拒绝不认识的格式（未来字段演进时递增） */
    const val FORMAT_VERSION = 1

    /** 密钥字段名（手写常量，避免收发两端拼写漂移） */
    const val FIELD_VERSION = "version"
    const val FIELD_ACCOUNTS = "accounts"
    const val FIELD_OAUTH_CLIENT_ID = "oauthClientId"

    fun toJson(accounts: List<ExportAccount>, oauthClientId: String?): String {
        val root = JSONObject()
        root.put(FIELD_VERSION, FORMAT_VERSION)
        val array = JSONArray()
        for ((account, secrets) in accounts) {
            val item = JSONObject()
            item.put("email", account.email)
            item.put("alias", account.alias)
            item.put("authType", account.authType.name)
            item.put("imapHost", account.imapHost)
            item.put("imapPort", account.imapPort)
            item.put("imapSecurity", account.imapSecurity.name)
            item.put("smtpHost", account.smtpHost)
            item.put("smtpPort", account.smtpPort)
            item.put("smtpSecurity", account.smtpSecurity.name)
            if (secrets.password.isNotBlank()) {
                item.put("password", secrets.password)
            }
            // OAuth2 令牌组（第一版手机端跳过该账户，但完整导出，供后续版本直接使用）
            secrets.oauth?.let { tokens ->
                item.put("oauthAccessToken", tokens.accessToken)
                tokens.refreshToken?.let { item.put("oauthRefreshToken", it) }
                item.put("oauthExpiresAt", tokens.expiresAtMillis)
                tokens.scope?.let { item.put("oauthScope", it) }
            }
            array.put(item)
        }
        root.put(FIELD_ACCOUNTS, array)
        if (!oauthClientId.isNullOrBlank()) {
            root.put(FIELD_OAUTH_CLIENT_ID, oauthClientId)
        }
        return root.toString()
    }
}
