package com.wm.wearmail.data.repo

import com.wm.wearmail.model.Draft
import kotlinx.coroutines.flow.Flow

/**
 * 草稿仓储。
 *
 * 离线暂存语义：发送失败或用户在无网络时保存，草稿写入本地库，
 * 待网络恢复后由 [com.wm.wearmail.sync.SyncService.flushPendingDrafts] 自动投递。
 */
interface DraftRepository {

    /** 观察全部草稿（按创建时间倒序） */
    fun observeDrafts(): Flow<List<Draft>>

    /** 观察待发送草稿（lastError 为 null） */
    fun observePending(): Flow<List<Draft>>

    /** 新增或更新草稿，返回草稿 id */
    suspend fun save(draft: Draft): Long

    /** 读取单条草稿 */
    suspend fun draft(id: Long): Draft?

    /** 删除草稿（发送成功后调用） */
    suspend fun delete(id: Long)

    /** 记录发送失败原因（null 表示清除错误，重新进入待发送队列） */
    suspend fun markError(id: Long, error: String?)

    /** 待发送草稿列表（同步引擎使用，非 Flow） */
    suspend fun pending(): List<Draft>

    suspend fun deleteByAccount(accountId: Long)
}
