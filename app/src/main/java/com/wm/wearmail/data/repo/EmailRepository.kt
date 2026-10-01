package com.wm.wearmail.data.repo

import com.wm.wearmail.model.EmailMeta
import kotlinx.coroutines.flow.Flow

/**
 * 邮件仓储（统一收件箱的唯一数据源）。
 *
 * 关键行为：
 * - [upsertAll] 以 `(accountId, folder, uid)` 为唯一键去重；
 * - [observeInbox] 按时间倒序合并所有账户，[accountId] 为 null 时即「全部账户」；
 * - [enforceLimits] 实现缓存上限的 LRU 淘汰（元数据 500 封 / 正文 50 封）。
 */
interface EmailRepository {

    /**
     * 观察统一收件箱。
     *
     * @param accountId null 表示不过滤（全部账户）
     * @param limit 最多返回条数，默认取缓存上限
     */
    fun observeInbox(accountId: Long? = null, limit: Int = 500): Flow<List<EmailMeta>>

    /** 观察未读数（[accountId] 为 null 时为全部账户之和） */
    fun observeUnreadCount(accountId: Long? = null): Flow<Int>

    /** 观察指定邮件（详情页使用；邮件被删除时发射 null） */
    fun observeEmail(emailId: Long): Flow<EmailMeta?>

    /**
     * 批量写入/更新邮件元数据。
     *
     * @return 新插入的条数（已存在的记录只更新状态，不计入）
     */
    suspend fun upsertAll(mails: List<EmailMeta>): Int

    /** 查询某账户某文件夹已同步的最大 UID（增量同步基准，0 表示尚未同步） */
    suspend fun latestUid(accountId: Long, folder: String): Long

    /** 查询最近一封已同步邮件的 UID（用于 IDLE 推送判定） */
    suspend fun maxUidInFolder(accountId: Long, folder: String): Long

    suspend fun email(emailId: Long): EmailMeta?

    /** 读取已缓存正文（未缓存返回 null） */
    suspend fun cachedBody(emailId: Long): String?

    /** 写入正文缓存 */
    suspend fun cacheBody(emailId: Long, text: String)

    suspend fun setRead(emailId: Long, read: Boolean)

    suspend fun setFlagged(emailId: Long, flagged: Boolean)

    /**
     * 删除本地邮件记录（同时删除正文缓存）。
     * 远端删除由 [com.wm.wearmail.sync.SyncService] 先行完成。
     */
    suspend fun delete(emailId: Long)

    /** 清空某账户的全部缓存数据（删除账户时调用） */
    suspend fun deleteByAccount(accountId: Long)

    /** 本地邮件总数（用于设置页展示缓存占用） */
    suspend fun totalCount(): Int

    /** 本地正文缓存数量 */
    suspend fun bodyCount(): Int

    /**
     * 执行 LRU 淘汰：超出 [maxHeaders] / [maxBodies] 的部分按
     * 「最久未访问」优先删除。
     *
     * @return 本次淘汰的记录条数
     */
    suspend fun enforceLimits(maxHeaders: Int, maxBodies: Int): Int
}
