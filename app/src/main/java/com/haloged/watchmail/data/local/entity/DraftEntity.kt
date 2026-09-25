package com.haloged.watchmail.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 草稿实体类
 * 存储未发送的邮件草稿
 */
@Entity(
    tableName = "drafts",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"])
    ]
)
data class DraftEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 发件账户ID
    val accountId: Long,
    
    // 收件人
    val toAddress: String,
    
    // 邮件主题
    val subject: String,
    
    // 邮件正文
    val body: String,
    
    // 是否正在发送中
    val isSending: Boolean = false,
    
    // 发送失败次数
    val failCount: Int = 0,
    
    // 最后错误信息
    val lastError: String? = null,
    
    // 创建时间
    val createdAt: Long = System.currentTimeMillis(),
    
    // 更新时间
    val updatedAt: Long = System.currentTimeMillis()
)
