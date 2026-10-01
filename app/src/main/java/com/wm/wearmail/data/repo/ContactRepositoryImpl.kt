package com.wm.wearmail.data.repo

import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.db.ContactDao
import com.wm.wearmail.data.db.MailDatabase
import com.wm.wearmail.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 常用联系人仓储实现。
 *
 * 线程模型：DAO 同步执行，本类统一 [Dispatchers.IO]；
 * 可观察性使用**版本号 Flow**（见 [EmailRepositoryImpl] 的说明）。
 *
 * 隐私约定：联系人属于用户隐私，日志只记录条数，不打印地址与姓名。
 */
class ContactRepositoryImpl(private val db: MailDatabase) : ContactRepository {

    private val contactDao = ContactDao(db)

    /** 数据版本号：写操作后自增，驱动 observeFrequent 重新查询 */
    private val version = MutableStateFlow(0L)

    private fun bumpVersion() {
        version.value += 1L
    }

    override fun observeFrequent(limit: Int): Flow<List<Contact>> =
        version
            .map { withContext(Dispatchers.IO) { contactDao.queryFrequent(limit) } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun record(name: String?, address: String) = withContext(Dispatchers.IO) {
        val id = contactDao.upsert(name, address)
        if (id > 0L) {
            bumpVersion()
        }
        // 只记录行 id：姓名与地址属于用户隐私，不得写入日志
        Logs.d(TAG, "联系人使用已记录（id=$id）")
    }

    override suspend fun search(query: String, limit: Int): List<Contact> = withContext(Dispatchers.IO) {
        contactDao.search(query, limit)
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        contactDao.count()
    }

    companion object {
        private const val TAG = "ContactRepo"
    }
}
