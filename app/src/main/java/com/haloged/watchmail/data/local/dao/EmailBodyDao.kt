package com.haloged.watchmail.data.local.dao

import androidx.room.*
import com.haloged.watchmail.data.local.entity.EmailBodyEntity

/**
 * 邮件正文数据访问对象
 */
@Dao
interface EmailBodyDao {
    
    /**
     * 根据邮件ID获取正文
     */
    @Query("SELECT * FROM email_bodies WHERE emailId = :emailId LIMIT 1")
    suspend fun getBodyByEmailId(emailId: Long): EmailBodyEntity?
    
    /**
     * 刷新正文下载时间戳（LRU 触达）
     * 每次读取正文时调用，使该条目成为"最近使用"
     */
    @Query("UPDATE email_bodies SET downloadedAt = :now WHERE emailId = :emailId")
    suspend fun touchBody(emailId: Long, now: Long = System.currentTimeMillis())
    
    /**
     * 插入正文
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBody(body: EmailBodyEntity): Long
    
    /**
     * 更新正文
     */
    @Update
    suspend fun updateBody(body: EmailBodyEntity)
    
    /**
     * 删除正文
     */
    @Delete
    suspend fun deleteBody(body: EmailBodyEntity)
    
    /**
     * 根据邮件ID删除正文
     */
    @Query("DELETE FROM email_bodies WHERE emailId = :emailId")
    suspend fun deleteBodyByEmailId(emailId: Long)
    
    /**
     * 获取最旧的正文（用于LRU淘汰）
     */
    @Query("SELECT id FROM email_bodies ORDER BY downloadedAt ASC LIMIT :count")
    suspend fun getOldestBodyIds(count: Int): List<Long>
    
    /**
     * 删除最旧的正文
     */
    @Query("DELETE FROM email_bodies WHERE id IN (SELECT id FROM email_bodies ORDER BY downloadedAt ASC LIMIT :count)")
    suspend fun deleteOldestBodies(count: Int)
    
    /**
     * 获取正文总数
     */
    @Query("SELECT COUNT(*) FROM email_bodies")
    suspend fun getBodyCount(): Int
}
