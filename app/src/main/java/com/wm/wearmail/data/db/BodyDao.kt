package com.wm.wearmail.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.EmailBody

/**
 * 正文缓存表访问对象（`email_bodies`）。
 *
 * **线程约定**：全部方法同步执行，由
 * [com.wm.wearmail.data.repo.EmailRepositoryImpl] 用 `Dispatchers.IO` 包裹。
 *
 * 安全约定：正文属于用户隐私，日志中**只允许出现 emailId 与字符数**，
 * 绝不打印正文内容。
 */
class BodyDao(private val db: MailDatabase) {

    /**
     * 读取正文缓存，命中时顺带刷新 `last_access_at`（避免常用邮件被 LRU 误淘汰）。
     *
     * @return 正文文本；未缓存返回 null
     */
    fun query(emailId: Long): String? {
        val row: EmailBody? = db.read { d ->
            d.query(
                BodiesTable.NAME,
                arrayOf(BodiesTable.EMAIL_ID, BodiesTable.BODY_TEXT, BodiesTable.DOWNLOADED_AT),
                "${BodiesTable.EMAIL_ID} = ?",
                arrayOf(emailId.toString()),
                null,
                null,
                null,
            ).use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    EmailBody(
                        emailId = c.getLong(c.getColumnIndexOrThrow(BodiesTable.EMAIL_ID)),
                        text = c.getString(c.getColumnIndexOrThrow(BodiesTable.BODY_TEXT)).orEmpty(),
                        downloadedAt = c.getLong(c.getColumnIndexOrThrow(BodiesTable.DOWNLOADED_AT)),
                    )
                }
            }
        }
        if (row != null) {
            db.write { d ->
                val values = ContentValues().apply { put(BodiesTable.LAST_ACCESS_AT, System.currentTimeMillis()) }
                d.update(BodiesTable.NAME, values, "${BodiesTable.EMAIL_ID} = ?", arrayOf(emailId.toString()))
            }
        }
        return row?.text
    }

    /** 写入/覆盖正文缓存；`size_chars` 冗余存储，便于淘汰统计时不必读取大文本 */
    fun insertOrReplace(emailId: Long, text: String, downloadedAt: Long) {
        val values = ContentValues().apply {
            put(BodiesTable.EMAIL_ID, emailId)
            put(BodiesTable.BODY_TEXT, text)
            put(BodiesTable.DOWNLOADED_AT, downloadedAt)
            put(BodiesTable.LAST_ACCESS_AT, System.currentTimeMillis())
            put(BodiesTable.SIZE_CHARS, text.length)
        }
        db.write {
            it.insertWithOnConflict(BodiesTable.NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** 删除某封邮件的正文缓存 */
    fun deleteByEmail(emailId: Long) {
        db.write { it.delete(BodiesTable.NAME, "${BodiesTable.EMAIL_ID} = ?", arrayOf(emailId.toString())) }
    }

    /** 正文缓存条数（设置页展示缓存占用） */
    fun count(): Int = db.read { d ->
        d.rawQuery("SELECT COUNT(*) FROM ${BodiesTable.NAME}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * LRU 淘汰正文：按 `last_access_at ASC, downloaded_at ASC` 删除最久未访问的缓存。
     *
     * 实现说明：先用查询取出待删除主键（避免 SQLite 在 DELETE 中重复引用同一张表），
     * 再在事务内批量删除，返回受影响行数。
     *
     * @param keep 保留条数
     * @return 实际删除条数
     */
    fun evictOldest(keep: Int): Int {
        if (keep < 0) return 0
        val deleted = db.transaction { d ->
            val total = countIn(d)
            if (total <= keep) {
                0
            } else {
                val overflow = total - keep
                val ids = d.query(
                    BodiesTable.NAME,
                    arrayOf(BodiesTable.EMAIL_ID),
                    null,
                    null,
                    null,
                    null,
                    "${BodiesTable.LAST_ACCESS_AT} ASC, ${BodiesTable.DOWNLOADED_AT} ASC",
                    overflow.toString(),
                ).use { c ->
                    val list = ArrayList<String>(overflow)
                    while (c.moveToNext()) list.add(c.getLong(0).toString())
                    list
                }
                if (ids.isEmpty()) {
                    0
                } else {
                    d.delete(
                        BodiesTable.NAME,
                        "${BodiesTable.EMAIL_ID} IN (${placeholders(ids.size)})",
                        ids.toTypedArray(),
                    )
                }
            }
        }
        if (deleted > 0) {
            Logs.i(TAG, "正文缓存 LRU 淘汰 $deleted 条（保留上限 $keep）")
        }
        return deleted
    }

    private fun countIn(d: SQLiteDatabase): Int =
        d.rawQuery("SELECT COUNT(*) FROM ${BodiesTable.NAME}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    companion object {
        private const val TAG = "BodyDao"
    }
}
