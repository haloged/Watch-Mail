package com.wm.wearmail.data.repo

import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.db.BodyDao
import com.wm.wearmail.data.db.EmailDao
import com.wm.wearmail.data.db.MailDatabase
import com.wm.wearmail.model.EmailMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 邮件仓储实现（统一收件箱的唯一数据源）。
 *
 * 线程模型：DAO 为同步方法，本类统一用 [Dispatchers.IO] 包裹。
 *
 * 可观察性实现（不用 Room 的 LiveData/Flow）：
 * 用内部**版本号** [MutableStateFlow]<Long> 作为变更信号，
 * 每次写操作后自增；观察者 `map` 订阅版本号，每次变化时重新查询数据库，
 * 再用 `distinctUntilChanged` 过滤掉内容未变的重复发射。
 * 好处：SQL 查询结果本身即快照，不存在缓存一致性问题；
 * 代价：每次写入会触发一次全量查询——在本项目的 500 条上限下开销可接受。
 */
class EmailRepositoryImpl(private val db: MailDatabase) : EmailRepository {

    private val emailDao = EmailDao(db)
    private val bodyDao = BodyDao(db)

    /** 数据版本号：任何写操作后自增，驱动全部 observeXxx 重新查询 */
    private val version = MutableStateFlow(0L)

    /** 标记数据已变更（写入完成后调用） */
    private fun bumpVersion() {
        version.value += 1L
    }

    override fun observeInbox(accountId: Long?, limit: Int): Flow<List<EmailMeta>> =
        version
            // map 的 lambda 是 suspend 上下文，可直接调用挂起查询函数；
            // flowOn(Dispatchers.IO) 让整条上游（含 SQL 查询）都跑在 IO 线程
            .map { queryInboxForDisplay(accountId, limit) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override fun observeUnreadCount(accountId: Long?): Flow<Int> =
        version
            .map { withContext(Dispatchers.IO) { emailDao.unreadCount(accountId) } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override fun observeEmail(emailId: Long): Flow<EmailMeta?> =
        version
            // queryById 返回 null 即代表邮件已被删除，UI 据此关闭详情页
            .map { loadEmailForDisplay(emailId) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    /** 列表查询：顺带把这些条目的 last_access_at 前移，避免用户正在看的邮件被 LRU 淘汰 */
    private suspend fun queryInboxForDisplay(accountId: Long?, limit: Int): List<EmailMeta> =
        withContext(Dispatchers.IO) {
            val mails = emailDao.queryInbox(accountId, limit)
            val now = System.currentTimeMillis()
            mails.forEach { emailDao.touchAccess(it.id, now) }
            mails
        }

    /** 详情查询：同样刷新访问时间，保证正在阅读的邮件优先保留 */
    private suspend fun loadEmailForDisplay(emailId: Long): EmailMeta? = withContext(Dispatchers.IO) {
        val mail = emailDao.queryById(emailId)
        if (mail != null) emailDao.touchAccess(emailId, System.currentTimeMillis())
        mail
    }

    override suspend fun upsertAll(mails: List<EmailMeta>): Int = withContext(Dispatchers.IO) {
        val inserted = emailDao.upsertAll(mails)
        bumpVersion()
        // 兜底淘汰：调用方（同步引擎）通常还会按用户设置再调用一次 enforceLimits，
        // 这里用默认上限保证即使调用方忘记执行，缓存也不会无限增长。
        // 直接调用 DAO 而不是 enforceLimits()，避免嵌套 withContext 造成多余调度。
        val removedHeaders = emailDao.evictOldestHeaders(DEFAULT_MAX_HEADERS)
        val removedBodies = bodyDao.evictOldest(DEFAULT_MAX_BODIES)
        if (removedHeaders + removedBodies > 0) {
            Logs.i(TAG, "写入后兜底淘汰：元数据 $removedHeaders 条、正文 $removedBodies 条")
        }
        inserted
    }

    override suspend fun latestUid(accountId: Long, folder: String): Long = withContext(Dispatchers.IO) {
        emailDao.latestUid(accountId, folder)
    }

    override suspend fun maxUidInFolder(accountId: Long, folder: String): Long = withContext(Dispatchers.IO) {
        emailDao.maxUidInFolder(accountId, folder)
    }

    override suspend fun email(emailId: Long): EmailMeta? = withContext(Dispatchers.IO) {
        emailDao.queryById(emailId)
    }

    override suspend fun cachedBody(emailId: Long): String? = withContext(Dispatchers.IO) {
        // BodyDao.query 命中时会顺带刷新 last_access_at（LRU 保命）
        bodyDao.query(emailId)
    }

    override suspend fun cacheBody(emailId: Long, text: String) = withContext(Dispatchers.IO) {
        bodyDao.insertOrReplace(emailId, text, System.currentTimeMillis())
        Logs.d(TAG, "正文已缓存（emailId=$emailId，字符数=${text.length}）")
        bumpVersion()
    }

    override suspend fun setRead(emailId: Long, read: Boolean) = withContext(Dispatchers.IO) {
        emailDao.setRead(emailId, read)
        bumpVersion()
    }

    override suspend fun setFlagged(emailId: Long, flagged: Boolean) = withContext(Dispatchers.IO) {
        emailDao.setFlagged(emailId, flagged)
        bumpVersion()
    }

    override suspend fun delete(emailId: Long) = withContext(Dispatchers.IO) {
        // DAO 在同一事务内删除正文与元数据
        emailDao.delete(emailId)
        bumpVersion()
    }

    override suspend fun deleteByAccount(accountId: Long) = withContext(Dispatchers.IO) {
        emailDao.deleteByAccount(accountId)
        bumpVersion()
    }

    override suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        emailDao.totalCount()
    }

    override suspend fun bodyCount(): Int = withContext(Dispatchers.IO) {
        bodyDao.count()
    }

    override suspend fun enforceLimits(maxHeaders: Int, maxBodies: Int): Int = withContext(Dispatchers.IO) {
        val removedHeaders = emailDao.evictOldestHeaders(maxHeaders)
        val removedBodies = bodyDao.evictOldest(maxBodies)
        val total = removedHeaders + removedBodies
        if (total > 0) {
            Logs.i(TAG, "LRU 淘汰：元数据 $removedHeaders 条、正文 $removedBodies 条")
        }
        total
    }

    companion object {
        private const val TAG = "EmailRepo"

        /** 兜底上限：与 AppSettings 默认值保持一致（元数据 500 / 正文 50） */
        private const val DEFAULT_MAX_HEADERS = 500
        private const val DEFAULT_MAX_BODIES = 50
    }
}
