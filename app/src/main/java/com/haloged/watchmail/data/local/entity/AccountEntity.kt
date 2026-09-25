package com.haloged.watchmail.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 邮箱账户实体类
 * 存储邮箱账户的配置信息，密码等敏感信息需加密存储
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 邮箱地址
    val email: String,
    
    // 加密后的密码/应用专用密码
    val encryptedPassword: String,
    
    // 账户别名（如"工作"、"个人"）
    val alias: String,
    
    // IMAP服务器配置
    val imapHost: String,
    val imapPort: Int,
    val imapEncryption: EncryptionType,
    
    // SMTP服务器配置
    val smtpHost: String,
    val smtpPort: Int,
    val smtpEncryption: EncryptionType,
    
    // 账户标识颜色（用于UI显示）
    val color: Long,
    
    // 账户是否启用
    val isEnabled: Boolean = true,
    
    // 是否启用通知
    val notificationEnabled: Boolean = true,
    
    // 最后同步时间
    val lastSyncTime: Long = 0,
    
    // 创建时间
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 加密方式枚举
 */
enum class EncryptionType {
    SSL,
    TLS,
    STARTTLS,
    NONE
}
