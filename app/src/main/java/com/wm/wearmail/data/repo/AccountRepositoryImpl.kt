package com.wm.wearmail.data.repo

import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.crypto.CryptoManager
import com.wm.wearmail.data.db.AccountDao
import com.wm.wearmail.data.db.MailDatabase
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.OAuthTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 账户仓储实现。
 *
 * 线程模型：DAO 提供**同步**方法，本类统一用 [Dispatchers.IO] 包裹后对外暴露
 * `suspend` API；`accounts` 为内存快照 [StateFlow]，UI 订阅它即可避免每次读库。
 *
 * 安全模型（关键）：
 * - 明文凭据只在本类方法栈内短暂存在，写入数据库前一律经 [CryptoManager] 加密；
 * - 读取时只有拿到密文才解密，解密失败返回 null（提示用户重新输入密码）；
 * - 任何日志都不包含密码 / 令牌内容。
 */
class AccountRepositoryImpl(
    private val db: MailDatabase,
    private val crypto: CryptoManager,
) : AccountRepository {

    private val accountDao = AccountDao(db)

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    override suspend fun load(): List<Account> = withContext(Dispatchers.IO) {
        val list = accountDao.queryAll()
        _accounts.value = list
        Logs.d(TAG, "已加载 ${list.size} 个账户")
        list
    }

    override suspend fun add(account: Account, secrets: AccountSecrets): Long = withContext(Dispatchers.IO) {
        // 颜色索引为 0（未指定）时按邮箱哈希自动分配，保证多账户列表可区分
        val resolved = if (account.colorIndex == 0) {
            account.copy(colorIndex = Account.colorIndexFor(account.email))
        } else {
            account
        }
        val id = accountDao.insert(
            resolved,
            crypto.encryptNullable(secrets.password),
            // OAuth2 令牌组序列化成单行文本后整体加密（access + refresh + 过期时间）
            crypto.encryptNullable(secrets.oauth?.serialize()),
        )
        if (id > 0L) {
            refresh()
            Logs.i(TAG, "账户已添加（id=$id）")
        } else {
            Logs.w(TAG, "账户添加失败（邮箱可能已存在）")
        }
        id
    }

    override suspend fun update(account: Account, secrets: AccountSecrets?): Boolean = withContext(Dispatchers.IO) {
        // secrets == null 表示「不改动凭据」：向 DAO 传 null 密文即可保留原密文列
        val passwordEnc = secrets?.let { crypto.encryptNullable(it.password) }
        val tokenEnc = secrets?.let { crypto.encryptNullable(it.oauth?.serialize()) }
        val ok = accountDao.update(account, passwordEnc, tokenEnc)
        if (ok) {
            refresh()
            Logs.i(TAG, "账户已更新（id=${account.id}，凭据${if (secrets == null) "未改动" else "已更新"}）")
        } else {
            Logs.w(TAG, "账户更新失败：记录不存在（id=${account.id}）")
        }
        ok
    }

    override suspend fun delete(accountId: Long): Boolean = withContext(Dispatchers.IO) {
        // AccountDao.delete 内部在一个事务里清空 emails / email_bodies / drafts 再删账户，
        // 既依赖外键级联，也显式删除以兼容未启用外键的环境
        val ok = accountDao.delete(accountId)
        if (ok) {
            refresh()
            Logs.i(TAG, "账户及其缓存已删除（id=$accountId）")
        } else {
            Logs.w(TAG, "账户删除失败：记录不存在（id=$accountId）")
        }
        ok
    }

    override suspend fun account(id: Long): Account? = withContext(Dispatchers.IO) {
        accountDao.queryById(id)
    }

    override suspend fun secrets(accountId: Long): AccountSecrets? = withContext(Dispatchers.IO) {
        val row = accountDao.querySecretsRow(accountId)
        if (row == null) {
            // 账户不存在
            return@withContext null
        }
        val (passwordEnc, tokenEnc) = row
        if (passwordEnc == null && tokenEnc == null) {
            // 合法状态：例如尚未填写凭据 / 纯 OAuth 账户
            return@withContext AccountSecrets.EMPTY
        }
        val password = passwordEnc?.let { crypto.decrypt(it) }
        val tokenRaw = tokenEnc?.let { crypto.decrypt(it) }
        if (passwordEnc != null && password == null) {
            // 密钥丢失或密文损坏：返回 null 让 UI 引导重新输入密码，而不是给出空密码
            Logs.w(TAG, "账户 $accountId 的密码解密失败，需要用户重新输入")
            return@withContext null
        }
        if (tokenEnc != null && tokenRaw == null) {
            Logs.w(TAG, "账户 $accountId 的令牌解密失败（将按无令牌处理）")
        }
        // 解析失败（版本不符/字段缺失）同样按"无令牌"处理，由 UI 引导重新授权
        AccountSecrets(password = password.orEmpty(), oauth = OAuthTokens.parse(tokenRaw))
    }

    override suspend fun touchLastSync(accountId: Long, timestamp: Long) = withContext(Dispatchers.IO) {
        accountDao.touchLastSync(accountId, timestamp)
        // 同步时间会影响 UI 上的「刚刚同步」提示，因此刷新快照
        refresh()
    }

    override suspend fun setNotificationsEnabled(accountId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        accountDao.setNotifications(accountId, enabled)
        refresh()
    }

    /** 重新读取数据库并刷新内存快照（调用方须已在 IO 线程） */
    private fun refresh() {
        _accounts.value = accountDao.queryAll()
    }

    companion object {
        private const val TAG = "AccountRepo"
    }
}
