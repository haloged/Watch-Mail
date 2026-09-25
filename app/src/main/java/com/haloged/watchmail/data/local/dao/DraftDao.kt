package com.haloged.watchmail.data.local.dao

import androidx.room.*
import com.haloged.watchmail.data.local.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

/**
 * 草稿数据访问对象
 */
@Dao
interface DraftDao {
    
    /**
     * 获取所有草稿（实时观察）
     */
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun getAllDraftsFlow(): Flow<List<DraftEntity>>
    
    /**
     * 获取待发送的草稿
     */
    @Query("SELECT * FROM drafts WHERE isSending = 0 AND failCount < 3 ORDER BY createdAt ASC")
    suspend fun getPendingDrafts(): List<DraftEntity>
    
    /**
     * 根据ID获取草稿
     */
    @Query("SELECT * FROM drafts WHERE id = :draftId")
    suspend fun getDraftById(draftId: Long): DraftEntity?
    
    /**
     * 插入草稿
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: DraftEntity): Long
    
    /**
     * 更新草稿
     */
    @Update
    suspend fun updateDraft(draft: DraftEntity)
    
    /**
     * 删除草稿
     */
    @Delete
    suspend fun deleteDraft(draft: DraftEntity)
    
    /**
     * 根据ID删除草稿
     */
    @Query("DELETE FROM drafts WHERE id = :draftId")
    suspend fun deleteDraftById(draftId: Long)
    
    /**
     * 更新发送状态
     */
    @Query("UPDATE drafts SET isSending = :isSending WHERE id = :draftId")
    suspend fun updateSendingStatus(draftId: Long, isSending: Boolean)
    
    /**
     * 更新失败次数和错误信息
     */
    @Query("UPDATE drafts SET failCount = :failCount, lastError = :error WHERE id = :draftId")
    suspend fun updateFailInfo(draftId: Long, failCount: Int, error: String?)
    
    /**
     * 获取草稿总数
     */
    @Query("SELECT COUNT(*) FROM drafts")
    suspend fun getDraftCount(): Int
}
