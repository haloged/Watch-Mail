package com.wm.wearmail.data.repo

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import kotlinx.coroutines.flow.StateFlow

/**
 * 账户仓储。
 *
 * 约定：
 * - 凭据（密码/令牌）不以明文经过本接口的实现内部存储；
 *   写入数据库前必须由 [com.wm.wearmail.data.crypto.CryptoManager] 加密；
 * - [accounts] 是内存态快照流，UI 直接订阅即可，避免每次读数据库。
 */
interface AccountRepository {

    /** 已绑定账户列表（按创建时间升序），冷启动后由 [load] 预热 */
    val accounts: StateFlow<List<Account>>

    /** 从数据库重新加载并刷新 [accounts] 快照 */
    suspend fun load(): List<Account>

    /**
     * 新增账户。
     *
     * @return 新账户的本地 id
     */
    suspend fun add(account: Account, secrets: AccountSecrets): Long

    /**
     * 更新账户。
     *
     * [secrets] 为 null 表示「不改动凭据」（例如用户只改了别名）。
     * @return 是否更新成功（账户不存在返回 false）
     */
    suspend fun update(account: Account, secrets: AccountSecrets?): Boolean

    /** 删除账户及其全部邮件/正文/草稿缓存 */
    suspend fun delete(accountId: Long): Boolean

    /** 按 id 查询账户 */
    suspend fun account(id: Long): Account?

    /** 读取账户凭据（内部完成解密；解密失败返回 null） */
    suspend fun secrets(accountId: Long): AccountSecrets?

    /** 更新最近同步时间 */
    suspend fun touchLastSync(accountId: Long, timestamp: Long = System.currentTimeMillis())

    /** 切换账户通知开关 */
    suspend fun setNotificationsEnabled(accountId: Long, enabled: Boolean)
}
