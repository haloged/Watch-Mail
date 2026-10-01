package com.wm.wearmail.model

/**
 * 单个服务器端点配置（IMAP 或 SMTP）。
 */
data class ServerConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: MailSecurity,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: MailSecurity,
)

/**
 * 服务商预设：用于「添加账户」页自动填充服务器参数，降低手表端输入成本。
 *
 * @param id 稳定标识，用于持久化用户选择
 * @param label 展示名称
 * @param domainKeywords 邮箱域名关键字，用于自动匹配
 * @param config 服务器配置
 * @param authHint 该服务商获取专用密码的提示文案
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val domainKeywords: List<String>,
    val config: ServerConfig,
    val authHint: String,
)

/**
 * 常见邮箱服务商配置表。
 *
 * 端口选择遵循各家官方文档的「第三方客户端」推荐值：
 * - Gmail / QQ / 163：IMAP 993(SSL) + SMTP 465(SSL)
 * - Outlook / 企业邮箱：IMAP 993(SSL) + SMTP 587(STARTTLS)
 *
 * 若服务商策略调整，用户可在「添加账户」页手动修改任意字段，
 * 或使用自动探测（[com.wm.wearmail.mail.ServerProbe]）逐一尝试端口组合。
 */
object ProviderPresets {

    /** Gmail（需先在 Google 账号中开启两步验证并生成应用专用密码） */
    val GMAIL = ProviderPreset(
        id = "gmail",
        label = "Gmail",
        domainKeywords = listOf("gmail.com", "googlemail.com"),
        config = ServerConfig(
            imapHost = "imap.gmail.com",
            imapPort = 993,
            imapSecurity = MailSecurity.SSL_TLS,
            smtpHost = "smtp.gmail.com",
            smtpPort = 465,
            smtpSecurity = MailSecurity.SSL_TLS,
        ),
        authHint = "Google 账号需开启两步验证后生成「应用专用密码」",
    )

    /** Outlook / Hotmail / Live */
    val OUTLOOK = ProviderPreset(
        id = "outlook",
        label = "Outlook / Hotmail",
        domainKeywords = listOf("outlook.com", "hotmail.com", "live.com", "msn.com"),
        config = ServerConfig(
            imapHost = "outlook.office365.com",
            imapPort = 993,
            imapSecurity = MailSecurity.SSL_TLS,
            smtpHost = "smtp.office365.com",
            smtpPort = 587,
            smtpSecurity = MailSecurity.STARTTLS,
        ),
        authHint = "Microsoft 账号可能要求 OAuth2 或应用密码",
    )

    /** QQ 邮箱 */
    val QQ = ProviderPreset(
        id = "qq",
        label = "QQ 邮箱",
        domainKeywords = listOf("qq.com", "vip.qq.com", "foxmail.com"),
        config = ServerConfig(
            imapHost = "imap.qq.com",
            imapPort = 993,
            imapSecurity = MailSecurity.SSL_TLS,
            smtpHost = "smtp.qq.com",
            smtpPort = 465,
            smtpSecurity = MailSecurity.SSL_TLS,
        ),
        authHint = "在 QQ 邮箱设置中开启 IMAP/SMTP 服务并获取授权码",
    )

    /** 网易 163 / 126 邮箱 */
    val NETEASE = ProviderPreset(
        id = "netease",
        label = "163 / 126 邮箱",
        domainKeywords = listOf("163.com", "126.com", "yeah.net"),
        config = ServerConfig(
            imapHost = "imap.163.com",
            imapPort = 993,
            imapSecurity = MailSecurity.SSL_TLS,
            smtpHost = "smtp.163.com",
            smtpPort = 465,
            smtpSecurity = MailSecurity.SSL_TLS,
        ),
        authHint = "需在网易邮箱设置中开启 IMAP/SMTP 并使用「授权码」登录",
    )

    /** 企业邮箱 / 自建服务器：仅提供占位域名，具体地址由用户填写或自动探测 */
    val ENTERPRISE = ProviderPreset(
        id = "enterprise",
        label = "企业邮箱 / 自建",
        domainKeywords = emptyList(),
        config = ServerConfig(
            imapHost = "",
            imapPort = 993,
            imapSecurity = MailSecurity.SSL_TLS,
            smtpHost = "",
            smtpPort = 465,
            smtpSecurity = MailSecurity.SSL_TLS,
        ),
        authHint = "请向管理员索取 IMAP/SMTP 地址与端口",
    )

    /** 全部预设，顺序即 UI 展示顺序 */
    val all: List<ProviderPreset> = listOf(GMAIL, OUTLOOK, QQ, NETEASE, ENTERPRISE)

    /**
     * 依据邮箱地址自动匹配服务商预设。
     *
     * 使用后缀匹配而非 equals，以支持 `user@mail.qq.com` 这类子域名。
     * 未命中时返回 [ENTERPRISE]，由用户手动填写或触发自动探测。
     */
    fun detect(email: String): ProviderPreset {
        val domain = email.substringAfterLast('@', "").lowercase().trim()
        if (domain.isEmpty()) return ENTERPRISE
        return all.firstOrNull { preset ->
            preset.domainKeywords.any { keyword -> domain == keyword || domain.endsWith(".$keyword") }
        } ?: ENTERPRISE
    }

    /** 按 id 查找预设，找不到时返回 [ENTERPRISE] */
    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id } ?: ENTERPRISE
}
