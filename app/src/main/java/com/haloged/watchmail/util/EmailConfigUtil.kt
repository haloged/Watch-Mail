package com.haloged.watchmail.util

import com.haloged.watchmail.data.local.entity.EncryptionType

/**
 * 邮箱服务器配置
 * 包含IMAP和SMTP服务器信息
 */
data class EmailServerConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapEncryption: EncryptionType,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpEncryption: EncryptionType
)

/**
 * 邮箱配置工具类
 * 根据邮箱域名自动探测服务器配置
 */
object EmailConfigUtil {
    
    // 预定义的邮箱服务商配置
    private val KNOWN_PROVIDERS = mapOf(
        // Gmail
        "gmail.com" to EmailServerConfig(
            imapHost = "imap.gmail.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.gmail.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        ),
        // Outlook/Hotmail
        "outlook.com" to EmailServerConfig(
            imapHost = "outlook.office365.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.office365.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        ),
        "hotmail.com" to EmailServerConfig(
            imapHost = "outlook.office365.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.office365.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        ),
        // Yahoo
        "yahoo.com" to EmailServerConfig(
            imapHost = "imap.mail.yahoo.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.mail.yahoo.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        ),
        // QQ邮箱
        "qq.com" to EmailServerConfig(
            imapHost = "imap.qq.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.qq.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        ),
        // 163邮箱
        "163.com" to EmailServerConfig(
            imapHost = "imap.163.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.163.com",
            smtpPort = 465,
            smtpEncryption = EncryptionType.SSL
        ),
        // 126邮箱
        "126.com" to EmailServerConfig(
            imapHost = "imap.126.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.126.com",
            smtpPort = 465,
            smtpEncryption = EncryptionType.SSL
        ),
        // iCloud
        "icloud.com" to EmailServerConfig(
            imapHost = "imap.mail.me.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.mail.me.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        ),
        // AOL
        "aol.com" to EmailServerConfig(
            imapHost = "imap.aol.com",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.aol.com",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        )
    )
    
    /**
     * 根据邮箱地址自动获取服务器配置
     * @param email 邮箱地址
     * @return 服务器配置，如果无法识别则返回null
     */
    fun getConfigForEmail(email: String): EmailServerConfig? {
        val domain = email.substringAfter("@").lowercase()
        return KNOWN_PROVIDERS[domain]
    }
    
    /**
     * 生成通用配置（基于常见端口）
     * 用于未知邮箱服务商
     */
    fun generateGenericConfig(domain: String): EmailServerConfig {
        return EmailServerConfig(
            imapHost = "imap.$domain",
            imapPort = 993,
            imapEncryption = EncryptionType.SSL,
            smtpHost = "smtp.$domain",
            smtpPort = 587,
            smtpEncryption = EncryptionType.STARTTLS
        )
    }
    
    /**
     * 验证邮箱地址格式
     */
    fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        return email.matches(emailRegex.toRegex())
    }
    
    /**
     * 获取邮箱域名
     */
    fun getEmailDomain(email: String): String {
        return email.substringAfter("@").lowercase()
    }
    
    /**
     * 获取邮箱服务商显示名称
     */
    fun getProviderName(email: String): String {
        val domain = getEmailDomain(email)
        return when {
            domain.contains("gmail") -> "Gmail"
            domain.contains("outlook") || domain.contains("hotmail") -> "Outlook"
            domain.contains("yahoo") -> "Yahoo"
            domain.contains("qq") -> "QQ邮箱"
            domain.contains("163") -> "163邮箱"
            domain.contains("126") -> "126邮箱"
            domain.contains("icloud") -> "iCloud"
            domain.contains("aol") -> "AOL"
            else -> domain
        }
    }
}
