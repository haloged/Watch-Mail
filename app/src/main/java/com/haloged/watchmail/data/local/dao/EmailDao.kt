package com.haloged.watchmail.data.local.dao

import androidx.room.*
import com.haloged.watchmail.data.local.entity.EmailEntity
import kotlinx.coroutines.flow.Flow

/**
 * 邮件数据访问对象
 */
@Dao
interface EmailDao {
    
    /**
     * 获取所有邮件（按时间倒序，实时观察）
     */
    @Query("SELECT * FROM emails ORDER BY receivedAt DESC")
    fun getAllEmailsFlow(): Flow<List<EmailEntity>>
    
    /**
     * 获取指定账户的邮件
     */
    @Query("SELECT * FROM emails WHERE accountId = :accountId ORDER BY receivedAt DESC")
    fun getEmailsByAccountFlow(accountId: Long): Flow<List<EmailEntity>>
    
    /**
     * 获取指定账户的邮件（非Flow）
     */
    @Query("SELECT * FROM emails WHERE accountId = :accountId ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun getEmailsByAccount(accountId: Long, limit: Int = 50): List<EmailEntity>
    
    /**
     * 根据ID获取邮件
     */
    @Query("SELECT * FROM emails WHERE id = :emailId")
    suspend fun getEmailById(emailId: Long): EmailEntity?
    
    /**
     * 根据UID和账户ID获取邮件
     */
    @Query("SELECT * FROM emails WHERE uid = :uid AND accountId = :accountId LIMIT 1")
    suspend fun getEmailByUid(uid: Long, accountId: Long): EmailEntity?
    
    /**
     * 查询指定账户下已存在的 UID 集合
     * 用于精确统计"新增"邮件数量，避免 REPLACE 覆盖导致重复计数
     */
    @Query("SELECT uid FROM emails WHERE accountId = :accountId AND uid IN (:uids)")
    suspend fun getExistingUids(accountId: Long, uids: List<Long>): List<Long>
    
    /**
     * 根据ID获取邮件（Flow，用于详情页实时更新）
     */
    @Query("SELECT * FROM emails WHERE id = :emailId")
    fun getEmailByIdFlow(emailId: Long): Flow<EmailEntity?>
    
    /**
     * 刷新缓存时间戳（LRU 触达）
     * 每次读取邮件时调用，使该条目成为"最近使用"
     */
    @Query("UPDATE emails SET cachedAt = :now WHERE id = :emailId")
    suspend fun touchCachedAt(emailId: Long, now: Long = System.currentTimeMillis())
    
    /**
     * 批量刷新缓存时间戳
     */
    @Query("UPDATE emails SET cachedAt = :now WHERE id IN (:emailIds)")
    suspend fun touchCachedAtBatch(emailIds: List<Long>, now: Long = System.currentTimeMillis())
    
    /**
     * 获取未读邮件数量
     */
    @Query("SELECT COUNT(*) FROM emails WHERE isRead = 0")
    suspend fun getUnreadCount(): Int
    
    /**
     * 获取指定账户的未读邮件数量
     */
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND isRead = 0")
    suspend fun getUnreadCountByAccount(accountId: Long): Int
    
    /**
     * 获取最新的UID（用于增量同步）
     */
    @Query("SELECT MAX(uid) FROM emails WHERE accountId = :accountId AND folder = :folder")
    suspend fun getLatestUid(accountId: Long, folder: String = "INBOX"): Long?
    
    /**
     * 插入邮件
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmail(email: EmailEntity): Long
    
    /**
     * 批量插入邮件
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmails(emails: List<EmailEntity>): List<Long>
    
    /**
     * 更新邮件
     */
    @Update
    suspend fun updateEmail(email: EmailEntity)
    
    /**
     * 标记邮件为已读
     */
    @Query("UPDATE emails SET isRead = :isRead WHERE id = :emailId")
    suspend fun markAsRead(emailId: Long, isRead: Boolean = true)
    
    /**
     * 标记邮件为星标
     */
    @Query("UPDATE emails SET isStarred = :isStarred WHERE id = :emailId")
    suspend fun markAsStarred(emailId: Long, isStarred: Boolean)
    
    /**
     * 删除邮件
     */
    @Delete
    suspend fun deleteEmail(email: EmailEntity)
    
    /**
     * 根据ID删除邮件
     */
    @Query("DELETE FROM emails WHERE id = :emailId")
    suspend fun deleteEmailById(emailId: Long)
    
    /**
     * 删除指定账户的所有邮件
     */
    @Query("DELETE FROM emails WHERE accountId = :accountId")
    suspend fun deleteEmailsByAccount(accountId: Long)
    
    /**
     * 更新正文下载状态
     */
    @Query("UPDATE emails SET isBodyDownloaded = :downloaded WHERE id = :emailId")
    suspend fun updateBodyDownloaded(emailId: Long, downloaded: Boolean)
    
    /**
     * 获取最旧的邮件（用于LRU淘汰）
     */
    @Query("SELECT id FROM emails ORDER BY cachedAt ASC LIMIT :count")
    suspend fun getOldestEmailIds(count: Int): List<Long>
    
    /**
     * 删除最旧的邮件（保持缓存上限）
     */
    @Query("DELETE FROM emails WHERE id IN (SELECT id FROM emails ORDER BY cachedAt ASC LIMIT :count)")
    suspend fun deleteOldestEmails(count: Int)
    
    /**
     * 获取邮件总数
     */
    @Query("SELECT COUNT(*) FROM emails")
    suspend fun getEmailCount(): Int
    
    /**
     * 搜索邮件
     */
    @Query("""
        SELECT * FROM emails 
        WHERE subject LIKE '%' || :query || '%' 
        OR fromName LIKE '%' || :query || '%' 
        OR fromAddress LIKE '%' || :query || '%'
        ORDER BY receivedAt DESC
    """)
    fun searchEmailsFlow(query: String): Flow<List<EmailEntity>>
}
