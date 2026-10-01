package com.wm.wearmail.data.repo

import com.wm.wearmail.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * 常用联系人仓储。
 *
 * 手表端输入困难，因此撰稿页优先展示「最近/最常联系」的联系人。
 * 数据来源有两个：
 * 1. 收件箱同步时自动累积的发件人（[record] 由同步引擎调用）；
 * 2. 用户成功发信后记录收件人。
 */
interface ContactRepository {

    /** 观察常用联系人（按使用次数与最近使用时间排序） */
    fun observeFrequent(limit: Int = 8): Flow<List<Contact>>

    /** 记录一次联系人使用（已存在则累加计数） */
    suspend fun record(name: String?, address: String)

    /** 按姓名或地址模糊搜索（撰稿页手动输入时的候选） */
    suspend fun search(query: String, limit: Int = 10): List<Contact>

    /** 联系人总数 */
    suspend fun count(): Int
}
