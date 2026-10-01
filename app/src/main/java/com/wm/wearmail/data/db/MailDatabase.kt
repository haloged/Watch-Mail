package com.wm.wearmail.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wm.wearmail.core.Logs

/**
 * 本地 SQLite 数据库（手写 SQL，**刻意不使用 Room / KSP / kapt**）。
 *
 * 架构取舍：
 * - 手表端构建资源紧张，注解处理器（kapt/KSP）会显著拖慢增量编译；
 * - 本项目只有 5 张表、SQL 语句总量可控，手写 `SQLiteOpenHelper` 反而更透明、
 *   更容易做安全审查（例如确认 `password_enc` 只落密文）。
 *
 * 线程约定（全项目统一，**不要在各 DAO 里自行切线程**）：
 * - 本类只提供同步的 [read] / [write] / [transaction] 便捷方法，内部取
 *   `readableDatabase` / `writableDatabase`；SQLite 自身有连接锁，跨线程调用安全；
 * - **DAO 一律提供同步方法**（阻塞式），由仓储层（`data/repo`）用
 *   `withContext(Dispatchers.IO)` 包裹后对外暴露 `suspend` API。
 *   这样「线程调度」只有一处实现，DAO 也能被同步地复用。
 */
class MailDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /**
     * 供需要直接持有 helper 的调用方使用（等价于 `this`）。
     * 仓储与 DAO 应优先使用 [read] / [write]，避免遗漏 `use {}` 造成游标泄漏。
     */
    val helper: SQLiteOpenHelper
        get() = this

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 必须开启外键约束，否则删除 accounts 时 emails / email_bodies / drafts 不会级联删除
        db.setForeignKeyConstraintsEnabled(true)
        Logs.d(TAG, "外键约束已启用")
    }

    override fun onCreate(db: SQLiteDatabase) {
        Logs.i(TAG, "首次创建数据库 $DB_NAME v$DB_VERSION")
        db.execSQL(AccountsTable.CREATE_SQL)
        db.execSQL(EmailsTable.CREATE_SQL)
        db.execSQL(BodiesTable.CREATE_SQL)
        db.execSQL(DraftsTable.CREATE_SQL)
        db.execSQL(ContactsTable.CREATE_SQL)
        INDEX_SQL.forEach(db::execSQL)
        Logs.i(TAG, "建表完成：accounts / emails / email_bodies / drafts / contacts")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 为初始版本，暂无迁移路径；后续版本必须在此逐版本增量迁移，禁止 drop 数据。
        Logs.w(TAG, "数据库升级 $oldVersion -> $newVersion：暂无迁移脚本，保持现有结构")
    }

    // ------------------------------------------------------------------
    // 事务化读写便捷方法
    // ------------------------------------------------------------------

    /** 只读查询：[block] 内应使用 `cursor.use {}` 关闭游标 */
    fun <T> read(block: (SQLiteDatabase) -> T): T = block(readableDatabase)

    /** 写操作：[block] 内可自行调用 `beginTransaction` 组合多条语句 */
    fun <T> write(block: (SQLiteDatabase) -> T): T = block(writableDatabase)

    /**
     * 事务包裹的写操作：异常时自动回滚，正常返回时提交。
     * 批量写入（如 `EmailDao.upsertAll`）必须走这里，避免每条语句一次 fsync。
     */
    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val result = block(db)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val TAG = "MailDatabase"

        /** 数据库文件名（应用私有目录） */
        const val DB_NAME: String = "wearmail.db"

        /** 数据库版本：结构变更时必须 +1 并在 [onUpgrade] 中补迁移 */
        const val DB_VERSION: Int = 1

        /** 全部索引：字段与排序方向严格对应需求中的列表排序与 LRU 淘汰 */
        private val INDEX_SQL: List<String> = listOf(
            "CREATE INDEX IF NOT EXISTS idx_emails_date ON ${EmailsTable.NAME}(${EmailsTable.DATE_MILLIS} DESC)",
            "CREATE INDEX IF NOT EXISTS idx_emails_account ON ${EmailsTable.NAME}(${EmailsTable.ACCOUNT_ID}, ${EmailsTable.FOLDER})",
            "CREATE INDEX IF NOT EXISTS idx_emails_unread ON ${EmailsTable.NAME}(${EmailsTable.IS_READ})",
            "CREATE INDEX IF NOT EXISTS idx_bodies_access ON ${BodiesTable.NAME}(${BodiesTable.LAST_ACCESS_AT})",
            "CREATE INDEX IF NOT EXISTS idx_contacts_used ON ${ContactsTable.NAME}(${ContactsTable.USED_COUNT} DESC, ${ContactsTable.LAST_USED_AT} DESC)",
        )
    }
}

/**
 * 表 `accounts`：邮箱账户配置与**密文凭据**。
 *
 * 安全红线：`password_enc` / `oauth_token_enc` 只允许写入
 * `v1:base64(iv):base64(ct)` 格式的密文，任何情况下都不得写明文。
 */
object AccountsTable {
    const val NAME = "accounts"
    const val ID = "id"
    const val EMAIL = "email"
    const val ALIAS = "alias"
    const val AUTH_TYPE = "auth_type"
    const val IMAP_HOST = "imap_host"
    const val IMAP_PORT = "imap_port"
    const val IMAP_SECURITY = "imap_security"
    const val SMTP_HOST = "smtp_host"
    const val SMTP_PORT = "smtp_port"
    const val SMTP_SECURITY = "smtp_security"
    const val COLOR_INDEX = "color_index"
    const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val CREATED_AT = "created_at"
    const val LAST_SYNC_AT = "last_sync_at"

    /** 密文列：登录密码 / 应用专用密码，格式 v1:base64(iv):base64(ct) */
    const val PASSWORD_ENC = "password_enc"

    /** 密文列：OAuth2 令牌，格式同 [PASSWORD_ENC] */
    const val OAUTH_TOKEN_ENC = "oauth_token_enc"

    val CREATE_SQL: String = """
        CREATE TABLE IF NOT EXISTS $NAME (
            $ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $EMAIL TEXT NOT NULL UNIQUE,
            $ALIAS TEXT NOT NULL DEFAULT '',
            $AUTH_TYPE TEXT NOT NULL,
            $IMAP_HOST TEXT NOT NULL,
            $IMAP_PORT INTEGER NOT NULL,
            $IMAP_SECURITY TEXT NOT NULL,
            $SMTP_HOST TEXT NOT NULL,
            $SMTP_PORT INTEGER NOT NULL,
            $SMTP_SECURITY TEXT NOT NULL,
            $COLOR_INDEX INTEGER NOT NULL DEFAULT 0,
            $NOTIFICATIONS_ENABLED INTEGER NOT NULL DEFAULT 1,
            $CREATED_AT INTEGER NOT NULL,
            $LAST_SYNC_AT INTEGER NOT NULL DEFAULT 0,
            $PASSWORD_ENC TEXT,
            $OAUTH_TOKEN_ENC TEXT
        )
    """.trimIndent()
}

/**
 * 表 `emails`：邮件元数据缓存（不含正文）。
 *
 * 唯一键 `(account_id, folder, uid)` 保证增量同步幂等；
 * `last_access_at` 供 LRU 淘汰排序，`date_millis` 供列表按时间倒序展示。
 */
object EmailsTable {
    const val NAME = "emails"
    const val ID = "id"
    const val ACCOUNT_ID = "account_id"
    const val FOLDER = "folder"
    const val UID = "uid"
    const val MESSAGE_ID = "message_id"
    const val FROM_ADDRESS = "from_address"
    const val FROM_NAME = "from_name"

    /** 收件人列表序列化文本（编解码由 MimeAddressSupport 负责） */
    const val TO_ADDRESSES = "to_addresses"

    /** 抄送列表序列化文本（同上） */
    const val CC_ADDRESSES = "cc_addresses"
    const val SUBJECT = "subject"
    const val DATE_MILLIS = "date_millis"
    const val IS_READ = "is_read"
    const val IS_FLAGGED = "is_flagged"
    const val HAS_ATTACHMENTS = "has_attachments"
    const val SIZE_BYTES = "size_bytes"

    /** 最近一次被读取/打开的时间：LRU 淘汰依据 */
    const val LAST_ACCESS_AT = "last_access_at"

    val CREATE_SQL: String = """
        CREATE TABLE IF NOT EXISTS $NAME (
            $ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $ACCOUNT_ID INTEGER NOT NULL,
            $FOLDER TEXT NOT NULL,
            $UID INTEGER NOT NULL,
            $MESSAGE_ID TEXT,
            $FROM_ADDRESS TEXT NOT NULL,
            $FROM_NAME TEXT,
            $TO_ADDRESSES TEXT NOT NULL DEFAULT '',
            $CC_ADDRESSES TEXT NOT NULL DEFAULT '',
            $SUBJECT TEXT NOT NULL DEFAULT '',
            $DATE_MILLIS INTEGER NOT NULL,
            $IS_READ INTEGER NOT NULL DEFAULT 0,
            $IS_FLAGGED INTEGER NOT NULL DEFAULT 0,
            $HAS_ATTACHMENTS INTEGER NOT NULL DEFAULT 0,
            $SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
            $LAST_ACCESS_AT INTEGER NOT NULL DEFAULT 0,
            UNIQUE($ACCOUNT_ID, $FOLDER, $UID),
            FOREIGN KEY($ACCOUNT_ID) REFERENCES ${AccountsTable.NAME}(${AccountsTable.ID}) ON DELETE CASCADE
        )
    """.trimIndent()
}

/** 表 `email_bodies`：正文缓存（纯文本），按需下载、LRU 淘汰 */
object BodiesTable {
    const val NAME = "email_bodies"
    const val EMAIL_ID = "email_id"
    const val BODY_TEXT = "body_text"
    const val DOWNLOADED_AT = "downloaded_at"
    const val LAST_ACCESS_AT = "last_access_at"

    /** 正文字符数：淘汰统计与设置页展示用，避免为了计数读取大文本 */
    const val SIZE_CHARS = "size_chars"

    val CREATE_SQL: String = """
        CREATE TABLE IF NOT EXISTS $NAME (
            $EMAIL_ID INTEGER PRIMARY KEY,
            $BODY_TEXT TEXT NOT NULL,
            $DOWNLOADED_AT INTEGER NOT NULL,
            $LAST_ACCESS_AT INTEGER NOT NULL DEFAULT 0,
            $SIZE_CHARS INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY($EMAIL_ID) REFERENCES ${EmailsTable.NAME}(${EmailsTable.ID}) ON DELETE CASCADE
        )
    """.trimIndent()
}

/** 表 `drafts`：离线草稿；`last_error` 非空表示上次投递失败需重试 */
object DraftsTable {
    const val NAME = "drafts"
    const val ID = "id"
    const val ACCOUNT_ID = "account_id"

    /** 收件人原始输入文本（可能含多个地址，保持用户输入原样存储） */
    const val TO_ADDRESSES = "to_addresses"
    const val SUBJECT = "subject"
    const val BODY = "body"
    const val CREATED_AT = "created_at"
    const val LAST_ERROR = "last_error"

    val CREATE_SQL: String = """
        CREATE TABLE IF NOT EXISTS $NAME (
            $ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $ACCOUNT_ID INTEGER NOT NULL,
            $TO_ADDRESSES TEXT NOT NULL,
            $SUBJECT TEXT NOT NULL DEFAULT '',
            $BODY TEXT NOT NULL DEFAULT '',
            $CREATED_AT INTEGER NOT NULL,
            $LAST_ERROR TEXT,
            FOREIGN KEY($ACCOUNT_ID) REFERENCES ${AccountsTable.NAME}(${AccountsTable.ID}) ON DELETE CASCADE
        )
    """.trimIndent()
}

/** 表 `contacts`：常用联系人（收件箱同步时累积发件人 / 发信后记录收件人） */
object ContactsTable {
    const val NAME = "contacts"
    const val ID = "id"
    const val ADDRESS = "address"

    /** 姓名列（`name` 是 SQL 关键字，统一用常量引用避免拼接出错） */
    const val NAME_COL = "name"
    const val USED_COUNT = "used_count"
    const val LAST_USED_AT = "last_used_at"

    val CREATE_SQL: String = """
        CREATE TABLE IF NOT EXISTS $NAME (
            $ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $ADDRESS TEXT NOT NULL UNIQUE,
            $NAME_COL TEXT,
            $USED_COUNT INTEGER NOT NULL DEFAULT 0,
            $LAST_USED_AT INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()
}
