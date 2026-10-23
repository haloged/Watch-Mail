package com.wm.wearmail.phone.data

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.OAuthTokens
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机端的「从手表同步来的账户」。
 *
 * 只保留收信必需字段（手机端不发送邮件；SMTP 字段随附保存以备将来）。
 * 凭据二选一：密码/授权码（[password]）或 OAuth2 令牌组（[oauth]）；
 * **凭据仅以密文落盘**：本对象只在内存中短暂持有明文，持久化时整份 JSON
 * 经 Keystore 加密（见 [PhoneStore]）。
 */
data class SyncedAccount(
    val email: String,
    val alias: String = "",
    val authType: AuthType = AuthType.APP_PASSWORD,
    val imapHost: String,
    val imapPort: Int = 993,
    val imapSecurity: MailSecurity = MailSecurity.SSL_TLS,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val smtpSecurity: MailSecurity = MailSecurity.SSL_TLS,
    val password: String = "",
    /** OAuth2 令牌组（OAuth2 账户）；与 [password] 二选一 */
    val oauth: OAuthTokens? = null,
) {
    /** 是否有可用于收信的凭据 */
    val hasCredential: Boolean
        get() = password.isNotBlank() || oauth != null

    fun toAccount(): Account = Account(
        email = email,
        alias = alias,
        authType = authType,
        imapHost = imapHost,
        imapPort = imapPort,
        imapSecurity = imapSecurity,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpSecurity = smtpSecurity,
    )

    companion object {

        /** 与手表端 `PairingExport` 保持一致的格式版本 */
        const val CONFIG_VERSION = 1

        /**
         * 解析手表导出的配置 JSON（或本地加密存储的明文 JSON —— 同一 schema）。
         *
         * 支持两类账户：密码/授权码、OAuth2（access + refresh token，手机端自行刷新）。
         * 两类凭据都缺失的账户会被跳过。
         *
         * @return 格式不可识别（非法 JSON / 版本不符 / 没有 accounts）返回 null；
         *   合法但过滤后为空时返回空列表。
         */
        fun parseConfig(raw: String): List<SyncedAccount>? {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.optInt("version", -1) != CONFIG_VERSION) return null
            val array = root.optJSONArray("accounts") ?: return null
            val result = mutableListOf<SyncedAccount>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val email = item.optString("email").trim()
                val imapHost = item.optString("imapHost").trim()
                val password = item.optString("password")
                val oauth = parseOauth(item)
                if (email.isEmpty() || imapHost.isEmpty()) continue
                if (password.isEmpty() && oauth == null) continue
                result += SyncedAccount(
                    email = email,
                    alias = item.optString("alias"),
                    authType = runCatching {
                        AuthType.valueOf(item.optString("authType", "APP_PASSWORD"))
                    }.getOrDefault(AuthType.APP_PASSWORD),
                    imapHost = imapHost,
                    imapPort = item.optInt("imapPort", 993),
                    imapSecurity = runCatching {
                        MailSecurity.valueOf(item.optString("imapSecurity", "SSL_TLS"))
                    }.getOrDefault(MailSecurity.SSL_TLS),
                    smtpHost = item.optString("smtpHost"),
                    smtpPort = item.optInt("smtpPort", 465),
                    smtpSecurity = runCatching {
                        MailSecurity.valueOf(item.optString("smtpSecurity", "SSL_TLS"))
                    }.getOrDefault(MailSecurity.SSL_TLS),
                    password = password,
                    oauth = oauth,
                )
            }
            return result
        }

        /** 本地存储用（加密前的明文 JSON） */
        fun toJson(accounts: List<SyncedAccount>): String {
            val root = JSONObject().put("version", CONFIG_VERSION)
            val array = JSONArray()
            for (account in accounts) {
                array.put(JSONObject().apply {
                    put("email", account.email)
                    put("alias", account.alias)
                    put("authType", account.authType.name)
                    put("imapHost", account.imapHost)
                    put("imapPort", account.imapPort)
                    put("imapSecurity", account.imapSecurity.name)
                    put("smtpHost", account.smtpHost)
                    put("smtpPort", account.smtpPort)
                    put("smtpSecurity", account.smtpSecurity.name)
                    if (account.password.isNotBlank()) put("password", account.password)
                    account.oauth?.let { tokens ->
                        put("oauthAccessToken", tokens.accessToken)
                        tokens.refreshToken?.let { put("oauthRefreshToken", it) }
                        put("oauthExpiresAt", tokens.expiresAtMillis)
                        tokens.scope?.let { put("oauthScope", it) }
                    }
                })
            }
            root.put("accounts", array)
            return root.toString()
        }

        private fun parseOauth(item: JSONObject): OAuthTokens? {
            val access = item.optString("oauthAccessToken")
            val refresh = item.optString("oauthRefreshToken")
            if (access.isEmpty() && refresh.isEmpty()) return null
            return OAuthTokens(
                accessToken = access,
                refreshToken = refresh.takeIf { it.isNotBlank() },
                expiresAtMillis = item.optLong("oauthExpiresAt", 0L),
                scope = item.optString("oauthScope").takeIf { it.isNotBlank() },
            )
        }
    }
}

/** 手表二维码对应的连接信息（手机与手表处于同一局域网） */
data class PairingEndpoint(
    val host: String,
    val port: Int,
    val token: String,
) {
    /** 手机端拉取配置的地址（手表端 `PairingController` 的 /export 端点） */
    val exportUrl: String
        get() = "http://$host:$port/export?t=$token"
}

/**
 * 手表二维码地址解析（纯函数，可单元测试）。
 *
 * 二维码内容：`http://192.168.1.5:8080/?t=<一次性 token>`
 * （与浏览器扫码配对是同一个地址：浏览器打开网页，手机应用请求 /export）。
 */
object PairingUrl {

    fun parse(raw: String): PairingEndpoint? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
            return null
        }
        val host = uri.host
        if (host.isNullOrBlank()) return null
        // 只接受局域网私有地址：手表必然在其中；公网二维码（如「下载手机版」的 Release 页）
        // 不应当被当成同步码去请求 /export
        if (!isPrivateHost(host)) return null
        val port = if (uri.port > 0) uri.port else 80
        val token = tokenOf(uri.rawQuery) ?: return null
        if (token.isBlank()) return null
        return PairingEndpoint(host = host, port = port, token = token)
    }

    /** 是否局域网私有地址（10/8、172.16/12、192.168/16、127/8、localhost） */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim()
        if (h.equals("localhost", ignoreCase = true)) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 127 -> true
            else -> false
        }
    }

    private fun tokenOf(rawQuery: String?): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) {
                null
            } else if (pair.substring(0, idx) == "t") {
                pair.substring(idx + 1)
            } else {
                null
            }
        }
}
