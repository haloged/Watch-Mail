package com.wm.wearmail.model

/**
 * OAuth2 令牌组（access token + refresh token + 过期时间）。
 *
 * 存储约定：本对象经 [serialize] 变成单行文本后，由
 * [com.wm.wearmail.data.crypto.CryptoManager] 加密写入 `accounts.oauth_token_enc` 列 ——
 * 因此**不需要改数据库结构**，refresh token 同样以密文落盘。
 *
 * 为什么需要 refresh token：Microsoft 的 access token 只有约 1 小时有效期，
 * 邮箱客户端必须在到期前用 refresh token 换新，否则同步会突然报认证失败。
 */
data class OAuthTokens(
    val accessToken: String,
    /** 长期有效的刷新令牌（需要申请 offline_access 权限才会返回） */
    val refreshToken: String? = null,
    /** access token 过期时刻（毫秒时间戳）；0 表示未知（视为需要刷新） */
    val expiresAtMillis: Long = 0L,
    /** 实际授权的 scope（排障用） */
    val scope: String? = null,
) {

    /**
     * 是否需要在发起 IMAP/SMTP 连接前刷新。
     *
     * 提前 [REFRESH_SKEW_MILLIS] 刷新：手表网络可能很慢，若等到过期才刷，
     * 一次同步就会以"认证失败"告终。
     */
    fun needsRefresh(
        nowMillis: Long = System.currentTimeMillis(),
        skewMillis: Long = REFRESH_SKEW_MILLIS,
    ): Boolean = expiresAtMillis <= 0L || nowMillis + skewMillis >= expiresAtMillis

    /** 是否还能刷新（没有 refresh token 就只能重新授权） */
    val canRefresh: Boolean
        get() = !refreshToken.isNullOrBlank()

    /**
     * 序列化为单行文本：`v1|access|refresh|expiresAt|scope`。
     *
     * 用 `|` 作分隔符的原因：JWT 使用 base64url 字母表（`A-Za-z0-9-_` 与 `.`），
     * Microsoft 的 refresh token 同样是 base64 风格，均不含 `|`，因此无需转义。
     * scope 含空格与 URL，同样不含 `|`。
     */
    fun serialize(): String = buildString {
        append(FORMAT_VERSION)
        append(SEPARATOR)
        append(accessToken)
        append(SEPARATOR)
        append(refreshToken.orEmpty())
        append(SEPARATOR)
        append(expiresAtMillis)
        append(SEPARATOR)
        append(scope.orEmpty())
    }

    companion object {
        /** 提前刷新的时间窗（2 分钟） */
        const val REFRESH_SKEW_MILLIS: Long = 120_000L

        private const val FORMAT_VERSION = "v1"
        private const val SEPARATOR = "|"

        /**
         * 反序列化。
         *
         * 解析失败（版本不符、字段缺失、access token 为空）返回 null，
         * 由调用方决定是引导用户重新授权还是忽略该账户。
         */
        fun parse(raw: String?): OAuthTokens? {
            if (raw.isNullOrBlank()) return null
            // limit = 5：scope 里即使出现分隔符也不会截断前面的字段
            val parts = raw.split(SEPARATOR, limit = 5)
            if (parts.size != 5 || parts[0] != FORMAT_VERSION) return null

            val access = parts[1]
            if (access.isBlank()) return null

            return OAuthTokens(
                accessToken = access,
                refreshToken = parts[2].takeIf { it.isNotBlank() },
                expiresAtMillis = parts[3].toLongOrNull() ?: 0L,
                scope = parts[4].takeIf { it.isNotBlank() },
            )
        }
    }
}
