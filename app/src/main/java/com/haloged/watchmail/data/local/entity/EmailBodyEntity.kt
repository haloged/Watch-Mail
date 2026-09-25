package com.haloged.watchmail.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 邮件正文实体类
 * 按需下载的邮件正文内容
 */
@Entity(
    tableName = "email_bodies",
    foreignKeys = [
        ForeignKey(
            entity = EmailEntity::class,
            parentColumns = ["id"],
            childColumns = ["emailId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["emailId"], unique = true)
    ]
)
data class EmailBodyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 关联的邮件ID
    val emailId: Long,
    
    // 纯文本正文
    val bodyText: String,
    
    // HTML正文（可选，用于特殊渲染）
    val bodyHtml: String? = null,
    
    // 下载时间
    val downloadedAt: Long = System.currentTimeMillis()
)
