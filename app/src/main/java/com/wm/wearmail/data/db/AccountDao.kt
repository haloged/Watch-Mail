package com.wm.wearmail.data.db

import android.content.ContentValues
import android.database.Cursor
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity

/**
 * 账户表访问对象（`accounts`）。
 *
 * **线程约定**：本类全部方法都是同步（阻塞）方法，直接操作 SQLite；
 * 上层 [com.wm.wearmail.data.repo.AccountRepositoryImpl] 负责用
 * `withContext(Dispatchers.IO)` 包裹，DAO 自身不切线程。
 *
 * **安全约定**：本类只搬运密文（`password_enc` / `oauth_token_enc`），
 * 加解密一律由 [com.wm.wearmail.data.crypto.CryptoManager] 在仓储层完成，
 * 因此本类不可能把明文凭据写入数据库，也不会打印任何凭据内容。
 */
class AccountDao(private val db: MailDatabase) {

    /**
     * 插入账户。
     *
     * @param passwordEnc 已加密的密码密文（null 表示该账户不使用密码认证）
     * @param tokenEnc 已加密的 OAuth2 令牌密文
     * @return 新行 id，失败返回 -1
     */
    fun insert(account: Account, passwordEnc: String?, tokenEnc: String?): Long {
        val values = accountValues(account).apply {
            putNullable(AccountsTable.PASSWORD_ENC, passwordEnc)
            putNullable(AccountsTable.OAUTH_TOKEN_ENC, tokenEnc)
            put(AccountsTable.CREATED_AT, if (account.createdAt > 0L) account.createdAt else System.currentTimeMillis())
        }
        return try {
            db.write { it.insert(AccountsTable.NAME, null, values) }
        } catch (t: Throwable) {
            // 唯一约束冲突（同一邮箱重复添加）会走到这里；不打印邮箱以外的任何内容
            Logs.e(TAG, "插入账户失败（可能存在重复邮箱或约束冲突）", t)
            -1L
        }
    }

    /**
     * 全量更新账户非敏感字段。
     *
     * [passwordEnc] / [tokenEnc] 为 null 表示**不覆盖**原有密文列，
     * 这样「只改别名」的编辑流程不会误清空凭据。
     *
     * @return 是否命中并更新了记录
     */
    fun update(account: Account, passwordEnc: String?, tokenEnc: String?): Boolean {
        if (account.id <= 0L) {
            Logs.w(TAG, "update 收到 id<=0 的账户，已忽略")
            return false
        }
        val values = accountValues(account).apply {
            putNullable(AccountsTable.PASSWORD_ENC, passwordEnc)
            putNullable(AccountsTable.OAUTH_TOKEN_ENC, tokenEnc)
        }
        val rows = db.write {
            it.update(AccountsTable.NAME, values, "${AccountsTable.ID} = ?", arrayOf(account.id.toString()))
        }
        return rows > 0
    }

    /**
     * 删除账户。
     *
     * 外键级联（`ON DELETE CASCADE`）会带走 emails / drafts；
     * 但部分运行环境（或历史库）外键未启用，这里**显式删除**一遍关联数据兜底，
     * 保证删除账户后不会残留孤儿邮件与草稿。正文随 emails 级联删除。
     */
    fun delete(id: Long): Boolean {
        return db.transaction { d ->
            d.delete(BodiesTable.NAME, "${BodiesTable.EMAIL_ID} IN (SELECT ${EmailsTable.ID} FROM ${EmailsTable.NAME} WHERE ${EmailsTable.ACCOUNT_ID} = ?)", arrayOf(id.toString()))
            d.delete(EmailsTable.NAME, "${EmailsTable.ACCOUNT_ID} = ?", arrayOf(id.toString()))
            d.delete(DraftsTable.NAME, "${DraftsTable.ACCOUNT_ID} = ?", arrayOf(id.toString()))
            d.delete(AccountsTable.NAME, "${AccountsTable.ID} = ?", arrayOf(id.toString())) > 0
        }
    }

    /** 查询全部账户，按创建时间升序（列表展示顺序稳定） */
    fun queryAll(): List<Account> = db.read { d ->
        d.query(AccountsTable.NAME, PROJECTION, null, null, null, null, "${AccountsTable.CREATED_AT} ASC, ${AccountsTable.ID} ASC")
            .use { c -> c.mapRows(::mapAccount) }
    }

    /** 按主键查询账户 */
    fun queryById(id: Long): Account? = db.read { d ->
        d.query(AccountsTable.NAME, PROJECTION, "${AccountsTable.ID} = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) mapAccount(c) else null }
    }

    /**
     * 读取密文行：`first` = 密码密文，`second` = OAuth2 令牌密文。
     *
     * 返回 null 表示账户不存在（与「存在但两列都为 null」区分开）。
     */
    fun querySecretsRow(id: Long): Pair<String?, String?>? = db.read { d ->
        d.query(
            AccountsTable.NAME,
            arrayOf(AccountsTable.PASSWORD_ENC, AccountsTable.OAUTH_TOKEN_ENC),
            "${AccountsTable.ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                c.getStringOrNull(0) to c.getStringOrNull(1)
            }
        }
    }

    /** 更新最近同步成功时间 */
    fun touchLastSync(id: Long, ts: Long) {
        val values = ContentValues().apply { put(AccountsTable.LAST_SYNC_AT, ts) }
        db.write { it.update(AccountsTable.NAME, values, "${AccountsTable.ID} = ?", arrayOf(id.toString())) }
    }

    /** 切换账户级通知开关（0/1 存储） */
    fun setNotifications(id: Long, enabled: Boolean) {
        val values = ContentValues().apply { put(AccountsTable.NOTIFICATIONS_ENABLED, if (enabled) 1 else 0) }
        db.write { it.update(AccountsTable.NAME, values, "${AccountsTable.ID} = ?", arrayOf(id.toString())) }
    }

    /** 账户总数（设置页与首屏引导判断是否需要添加账户） */
    fun count(): Int = db.read { d ->
        d.rawQuery("SELECT COUNT(*) FROM ${AccountsTable.NAME}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 非敏感列的统一映射，避免 insert/update 两处字段不一致 */
    private fun accountValues(account: Account): ContentValues = ContentValues().apply {
        put(AccountsTable.EMAIL, account.email)
        put(AccountsTable.ALIAS, account.alias)
        put(AccountsTable.AUTH_TYPE, account.authType.name)
        put(AccountsTable.IMAP_HOST, account.imapHost)
        put(AccountsTable.IMAP_PORT, account.imapPort)
        put(AccountsTable.IMAP_SECURITY, account.imapSecurity.name)
        put(AccountsTable.SMTP_HOST, account.smtpHost)
        put(AccountsTable.SMTP_PORT, account.smtpPort)
        put(AccountsTable.SMTP_SECURITY, account.smtpSecurity.name)
        put(AccountsTable.COLOR_INDEX, account.colorIndex)
        put(AccountsTable.NOTIFICATIONS_ENABLED, if (account.notificationsEnabled) 1 else 0)
        put(AccountsTable.LAST_SYNC_AT, account.lastSyncAt)
    }

    private fun mapAccount(c: Cursor): Account = Account(
        id = c.getLong(c.getColumnIndexOrThrow(AccountsTable.ID)),
        email = c.getString(c.getColumnIndexOrThrow(AccountsTable.EMAIL)).orEmpty(),
        alias = c.getString(c.getColumnIndexOrThrow(AccountsTable.ALIAS)).orEmpty(),
        authType = enumOrDefault(c.getString(c.getColumnIndexOrThrow(AccountsTable.AUTH_TYPE)), AuthType.APP_PASSWORD),
        imapHost = c.getString(c.getColumnIndexOrThrow(AccountsTable.IMAP_HOST)).orEmpty(),
        imapPort = c.getInt(c.getColumnIndexOrThrow(AccountsTable.IMAP_PORT)),
        imapSecurity = enumOrDefault(c.getString(c.getColumnIndexOrThrow(AccountsTable.IMAP_SECURITY)), MailSecurity.SSL_TLS),
        smtpHost = c.getString(c.getColumnIndexOrThrow(AccountsTable.SMTP_HOST)).orEmpty(),
        smtpPort = c.getInt(c.getColumnIndexOrThrow(AccountsTable.SMTP_PORT)),
        smtpSecurity = enumOrDefault(c.getString(c.getColumnIndexOrThrow(AccountsTable.SMTP_SECURITY)), MailSecurity.SSL_TLS),
        colorIndex = c.getInt(c.getColumnIndexOrThrow(AccountsTable.COLOR_INDEX)),
        notificationsEnabled = c.getInt(c.getColumnIndexOrThrow(AccountsTable.NOTIFICATIONS_ENABLED)) != 0,
        createdAt = c.getLong(c.getColumnIndexOrThrow(AccountsTable.CREATED_AT)),
        lastSyncAt = c.getLong(c.getColumnIndexOrThrow(AccountsTable.LAST_SYNC_AT)),
    )

    companion object {
        private const val TAG = "AccountDao"

        private val PROJECTION: Array<String> = arrayOf(
            AccountsTable.ID,
            AccountsTable.EMAIL,
            AccountsTable.ALIAS,
            AccountsTable.AUTH_TYPE,
            AccountsTable.IMAP_HOST,
            AccountsTable.IMAP_PORT,
            AccountsTable.IMAP_SECURITY,
            AccountsTable.SMTP_HOST,
            AccountsTable.SMTP_PORT,
            AccountsTable.SMTP_SECURITY,
            AccountsTable.COLOR_INDEX,
            AccountsTable.NOTIFICATIONS_ENABLED,
            AccountsTable.CREATED_AT,
            AccountsTable.LAST_SYNC_AT,
        )
    }
}

// ----------------------------------------------------------------------
// 文件内共享的 Cursor / ContentValues 小工具（internal，不对外暴露 API）
// ----------------------------------------------------------------------

/** 读取可空文本列：SQL NULL 与空串语义不同，必须区分 */
internal fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

/** ContentValues 写入可空文本：null 会写成 SQL NULL，而不是字符串 "null" */
internal fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

/** 遍历游标并映射为列表（调用方负责 `use {}` 关闭游标） */
internal inline fun <T> Cursor.mapRows(map: (Cursor) -> T): List<T> {
    val result = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) {
        result.add(map(this))
    }
    return result
}

/** 枚举列解析：遇到未知值（降级安装/人工改库）时回退默认值，避免崩溃 */
internal inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    if (raw.isNullOrEmpty()) default else enumValues<T>().firstOrNull { it.name == raw } ?: default

/** 生成 `?,?,?` 形式的 IN 占位符，避免字符串拼接造成 SQL 注入 */
internal fun placeholders(count: Int): String = Array(count) { "?" }.joinToString(",")
