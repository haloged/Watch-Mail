package com.wm.wearmail.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Contact

/**
 * 常用联系人表访问对象（`contacts`）。
 *
 * **线程约定**：全部方法同步执行，由
 * [com.wm.wearmail.data.repo.ContactRepositoryImpl] 用 `Dispatchers.IO` 包裹。
 *
 * 手表端输入困难，因此撰稿页优先展示「最常联系 + 最近联系」的人；
 * `address` 唯一约束保证同一邮箱不会重复占位，只累加计数。
 */
class ContactDao(private val db: MailDatabase) {

    /**
     * 记录一次联系人使用：不存在则插入（计数 1），已存在则 `used_count + 1`
     * 并刷新 `last_used_at`。
     *
     * 说明：`name` 为 null/空时优先保留库中已有的非空姓名，避免被后续
     * 「只知其地址」的记录把姓名覆盖掉。
     *
     * @return 联系人行 id，失败返回 -1
     */
    fun upsert(name: String?, address: String): Long {
        val normalized = address.trim()
        if (normalized.isEmpty()) {
            Logs.w(TAG, "忽略空地址的联系人记录")
            return -1L
        }
        val now = System.currentTimeMillis()
        return try {
            db.transaction { d ->
                val existing = queryIdAndName(d, normalized)
                if (existing != null) {
                    val values = ContentValues().apply {
                        put(ContactsTable.USED_COUNT, existing.usedCount + 1)
                        put(ContactsTable.LAST_USED_AT, now)
                        val newName = name?.takeIf { it.isNotBlank() } ?: existing.name
                        putNullable(ContactsTable.NAME_COL, newName)
                    }
                    d.update(ContactsTable.NAME, values, "${ContactsTable.ADDRESS} = ?", arrayOf(normalized))
                    existing.id
                } else {
                    val values = ContentValues().apply {
                        put(ContactsTable.ADDRESS, normalized)
                        putNullable(ContactsTable.NAME_COL, name?.takeIf { it.isNotBlank() })
                        put(ContactsTable.USED_COUNT, 1)
                        put(ContactsTable.LAST_USED_AT, now)
                    }
                    d.insertWithOnConflict(ContactsTable.NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        } catch (t: Throwable) {
            Logs.e(TAG, "写入联系人失败", t)
            -1L
        }
    }

    /** 常用联系人：先按使用次数，其次按最近使用时间倒序 */
    fun queryFrequent(limit: Int): List<Contact> = db.read { d ->
        d.query(
            ContactsTable.NAME,
            PROJECTION,
            null,
            null,
            null,
            null,
            "${ContactsTable.USED_COUNT} DESC, ${ContactsTable.LAST_USED_AT} DESC",
            limit.coerceAtLeast(1).toString(),
        ).use { c -> c.mapRows(::mapContact) }
    }

    /**
     * 模糊搜索：`address` 或 `name` 包含 [query]（大小写不敏感，LIKE 通配符已转义）。
     * 结果同样按常用度排序。
     */
    fun search(query: String, limit: Int): List<Contact> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return emptyList()
        val pattern = "%${escapeLike(keyword)}%"
        return db.read { d ->
            d.query(
                ContactsTable.NAME,
                PROJECTION,
                "(${ContactsTable.ADDRESS} LIKE ? ESCAPE '\\' OR ${ContactsTable.NAME_COL} LIKE ? ESCAPE '\\')",
                arrayOf(pattern, pattern),
                null,
                null,
                "${ContactsTable.USED_COUNT} DESC, ${ContactsTable.LAST_USED_AT} DESC",
                limit.coerceAtLeast(1).toString(),
            ).use { c -> c.mapRows(::mapContact) }
        }
    }

    /** 联系人总数（设置页清理提示） */
    fun count(): Int = db.read { d ->
        d.rawQuery("SELECT COUNT(*) FROM ${ContactsTable.NAME}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 查询已存在联系人的 id / 姓名 / 使用次数（用于累加更新） */
    private fun queryIdAndName(d: SQLiteDatabase, address: String): ContactSnapshot? =
        d.query(
            ContactsTable.NAME,
            arrayOf(ContactsTable.ID, ContactsTable.NAME_COL, ContactsTable.USED_COUNT),
            "${ContactsTable.ADDRESS} = ?",
            arrayOf(address),
            null,
            null,
            null,
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                ContactSnapshot(
                    id = c.getLong(0),
                    name = c.getStringOrNull(1),
                    usedCount = c.getInt(2),
                )
            }
        }

    /** LIKE 通配符转义：避免用户输入 `%` / `_` 变成通配导致全表匹配 */
    private fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun mapContact(c: Cursor): Contact = Contact(
        id = c.getLong(c.getColumnIndexOrThrow(ContactsTable.ID)),
        name = c.getStringOrNull(c.getColumnIndexOrThrow(ContactsTable.NAME_COL)),
        address = c.getString(c.getColumnIndexOrThrow(ContactsTable.ADDRESS)).orEmpty(),
        usedCount = c.getInt(c.getColumnIndexOrThrow(ContactsTable.USED_COUNT)),
        lastUsedAt = c.getLong(c.getColumnIndexOrThrow(ContactsTable.LAST_USED_AT)),
    )

    /** 已存在联系人的最小快照，避免在事务里构造完整 [Contact] */
    private data class ContactSnapshot(val id: Long, val name: String?, val usedCount: Int)

    companion object {
        private const val TAG = "ContactDao"

        private val PROJECTION: Array<String> = arrayOf(
            ContactsTable.ID,
            ContactsTable.ADDRESS,
            ContactsTable.NAME_COL,
            ContactsTable.USED_COUNT,
            ContactsTable.LAST_USED_AT,
        )
    }
}
