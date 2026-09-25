package com.haloged.watchmail.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 邮件元数据实体类
 * 存储邮件的基本信息，正文按需下载
 */
@Entity(
    tableName = "emails",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["uid", "accountId"], unique = true),
        Index(value = ["receivedAt"])
    ]
)
data class EmailEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 关联的账户ID
    val accountId: Long,
    
    // IMAP UID（用于增量同步）
    val uid: Long,
    
    // 邮件文件夹（如INBOX）
    val folder: String = "INBOX",
    
    // 发件人
    val fromAddress: String,
    val fromName: String? = null,
    
    // 收件人
    val toAddress: String,
    
    // 邮件主题
    val subject: String,
    
    // 邮件预览（正文前100字符）
    val preview: String = "",
    
    // 接收时间戳
    val receivedAt: Long,
    
    // 是否已读
    val isRead: Boolean = false,
    
    // 是否已星标
    val isStarred: Boolean = false,
    
    // 是否有附件
    val hasAttachment: Boolean = false,
    
    // IMAP Flags
    val flags: String = "",
    
    // 是否已下载正文
    val isBodyDownloaded: Boolean = false,
    
    // 本地缓存时间
    val cachedAt: Long = System.currentTimeMillis()
)
