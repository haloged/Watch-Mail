package com.wm.wearmail.model

/**
 * 邮件传输加密方式。
 *
 * - [SSL_TLS]：全程加密（IMAP 993 / SMTP 465），建立连接即为 TLS 通道；
 * - [STARTTLS]：明文端口（IMAP 143 / SMTP 587）先建连，再用 STARTTLS 升级为加密；
 * - [NONE]：不加密，仅在企业内网自建服务器等特殊场景使用。
 */
enum class MailSecurity(val label: String) {
    SSL_TLS("SSL/TLS"),
    STARTTLS("STARTTLS"),
    NONE("不加密"),
}

/**
 * 认证方式。
 *
 * Gmail / Outlook / QQ / 163 等主流服务商在「第三方客户端」场景下
 * 普遍要求使用应用专用密码或 OAuth2 令牌，而不是账号登录密码。
 */
enum class AuthType(val label: String) {
    PASSWORD("登录密码"),
    APP_PASSWORD("应用专用密码"),
    OAUTH2("OAuth2 令牌"),
}

/**
 * 邮箱账户配置（不含任何明文凭据）。
 *
 * 安全约定：本类**绝不**包含密码/令牌字段；凭据统一由
 * [com.wm.wearmail.data.crypto.AccountSecrets] 承载，并以加密形式落盘。
 * 这样即使账户对象被打印到日志或写入数据库的非加密列，也不会泄露敏感信息。
 */
data class Account(
    /** 本地自增主键，0 表示尚未入库 */
    val id: Long = 0L,
    /** 邮箱地址，同时作为 IMAP/SMTP 登录名 */
    val email: String,
    /** 账户别名，例如「工作」「个人」；为空时回退显示邮箱地址 */
    val alias: String = "",
    val authType: AuthType = AuthType.APP_PASSWORD,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: MailSecurity = MailSecurity.SSL_TLS,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: MailSecurity = MailSecurity.SSL_TLS,
    /** 账户标识色索引（0..COLOR_COUNT-1），用于列表中区分来源账户 */
    val colorIndex: Int = 0,
    /** 该账户是否允许触发新邮件通知 */
    val notificationsEnabled: Boolean = true,
    val createdAt: Long = 0L,
    /** 最近一次同步成功时间，0 表示从未同步 */
    val lastSyncAt: Long = 0L,
) {

    /** 列表中展示的账户名称：优先别名，其次邮箱 @ 前的部分 */
    val displayLabel: String
        get() = when {
            alias.isNotBlank() -> alias
            email.contains('@') -> email.substringBefore('@')
            else -> email
        }

    /** 账户首字母（大写），用于圆形标识中的文字标签 */
    val initial: String
        get() = email.firstOrNull()?.uppercase() ?: "?"

    companion object {
        /** 收件箱文件夹名（IMAP 规范固定值） */
        const val FOLDER_INBOX: String = "INBOX"

        /** 可选账户标识色数量 */
        const val COLOR_COUNT: Int = 8

        /** 依据稳定种子（如邮箱地址哈希）分配账户颜色索引 */
        fun colorIndexFor(seed: String): Int {
            if (seed.isEmpty()) return 0
            val hash = seed.lowercase().fold(0) { acc, c -> acc * 31 + c.code }
            return ((hash % COLOR_COUNT) + COLOR_COUNT) % COLOR_COUNT
        }
    }
}

/**
 * 账户凭据容器（内存态）。
 *
 * 注意：该对象仅在内存中短暂存在，写入数据库前必须经过
 * [com.wm.wearmail.data.crypto.CryptoManager] 加密。
 */
data class AccountSecrets(
    /** 登录密码 / 应用专用密码；OAuth2 账户为空 */
    val password: String = "",
    /**
     * OAuth2 令牌组（access token + refresh token + 过期时间）。
     *
     * 与 [password] 二选一：OAuth2 账户没有密码，靠长期有效的 refresh token 续期。
     * 落盘时整个令牌组会被序列化后**加密**（`accounts.oauth_token_enc` 列），
     * 因此不需要为 refresh token 新增数据库字段。
     */
    val oauth: OAuthTokens? = null,
) {
    val isEmpty: Boolean
        get() = password.isBlank() && oauth == null

    /**
     * 交给 IMAP/SMTP 的凭据口令。
     *
     * OAuth2 账户返回 access token —— SASL XOAUTH2 要求把令牌放在"密码"位置，
     * 由 JavaMail 拼成 `user=<邮箱>^Aauth=Bearer <token>^A^A`。
     */
    val authSecret: String
        get() = oauth?.accessToken ?: password

    companion object {
        val EMPTY = AccountSecrets()
    }
}
