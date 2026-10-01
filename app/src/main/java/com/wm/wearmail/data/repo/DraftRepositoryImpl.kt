package com.wm.wearmail.data.repo

import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.db.DraftDao
import com.wm.wearmail.data.db.MailDatabase
import com.wm.wearmail.model.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 草稿仓储实现。
 *
 * 线程模型：DAO 同步执行，本类统一 [Dispatchers.IO]；
 * 可观察性同样使用**版本号 Flow**（见 [EmailRepositoryImpl] 的说明）。
 *
 * 隐私约定：日志中只出现草稿 id 与账户 id，绝不打印收件人、主题或正文。
 */
class DraftRepositoryImpl(private val db: MailDatabase) : DraftRepository {

    private val draftDao = DraftDao(db)

    /** 数据版本号：写操作后自增，驱动 observeDrafts / observePending 重新查询 */
    private val version = MutableStateFlow(0L)

    private fun bumpVersion() {
        version.value += 1L
    }

    override fun observeDrafts(): Flow<List<Draft>> =
        version
            .map { withContext(Dispatchers.IO) { draftDao.queryAll() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override fun observePending(): Flow<List<Draft>> =
        version
            .map { withContext(Dispatchers.IO) { draftDao.queryPending() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun save(draft: Draft): Long = withContext(Dispatchers.IO) {
        val id = draftDao.insertOrUpdate(draft)
        bumpVersion()
        Logs.d(TAG, "草稿已保存（id=$id）")
        id
    }

    override suspend fun draft(id: Long): Draft? = withContext(Dispatchers.IO) {
        draftDao.queryById(id)
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        draftDao.delete(id)
        bumpVersion()
        Logs.d(TAG, "草稿已删除（id=$id）")
    }

    override suspend fun markError(id: Long, error: String?) = withContext(Dispatchers.IO) {
        draftDao.markError(id, error)
        bumpVersion()
        // 只记录「有错误 / 错误已清除」，不打印错误文本中可能包含的服务器回显内容之外的细节
        Logs.d(TAG, if (error == null) "草稿 $id 错误已清除，重新进入待发送队列" else "草稿 $id 标记为发送失败")
    }

    override suspend fun pending(): List<Draft> = withContext(Dispatchers.IO) {
        draftDao.queryPending()
    }

    override suspend fun deleteByAccount(accountId: Long) = withContext(Dispatchers.IO) {
        draftDao.deleteByAccount(accountId)
        bumpVersion()
    }

    companion object {
        private const val TAG = "DraftRepo"
    }
}
