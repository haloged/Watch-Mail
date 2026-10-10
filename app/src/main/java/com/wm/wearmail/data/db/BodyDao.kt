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

    /**
     * 读取缓存的**原始 HTML**。
     *
     * 返回 null 表示「没有可渲染的 HTML」—— 可能是没问过服务器，也可能是问了但没有，
     * 需要区分时用 [queryHtmlState]。HTML 动辄几十到几百 KB，只显示纯文本时不该把它读进内存。
     */
    fun queryHtml(emailId: Long): String? = db.read { d ->
        d.query(
            BodiesTable.NAME,
            arrayOf(BodiesTable.BODY_HTML),
            "${BodiesTable.EMAIL_ID} = ?",
            arrayOf(emailId.toString()),
            null,
            null,
            null,
        ).use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotEmpty() } else null
        }
    }

    /**
     * HTML 缓存的三态（见 [HtmlCacheState]）。
     *
     * 用途：判断「是否还需要为了 HTML 联网」—— [HtmlCacheState.UNKNOWN] 才需要补取一次，
     * [HtmlCacheState.NONE] 说明这封邮件本来就没有可渲染的 HTML，不该反复重下正文。
     */
    fun queryHtmlState(emailId: Long): HtmlCacheState = db.read { d ->
        d.query(
            BodiesTable.NAME,
            arrayOf(BodiesTable.BODY_HTML),
            "${BodiesTable.EMAIL_ID} = ?",
            arrayOf(emailId.toString()),
            null,
            null,
            null,
        ).use { c ->
            if (c.moveToFirst()) HtmlCacheState.fromColumnValue(c.getString(0)) else HtmlCacheState.UNKNOWN
        }
    }

    /**
     * 写入/覆盖正文缓存。
     *
     * @param html 原始 HTML；null 表示本次没拿到
     * @param htmlRequested 本次是否确实请求了 HTML。为 true 且 [html] 为 null 时会记下
     *   [HtmlCacheState.NONE]（「这封邮件没有可渲染的 HTML」），否则记为
     *   [HtmlCacheState.UNKNOWN]（下次还可以补取）。这条区分是必需的：
     *   少了它，纯文本邮件会被反复判定为「缓存不完整」，既白下载又无法离线阅读。
     *   `size_chars` 只统计纯文本（LRU 淘汰按访问时间，不依赖字符数）。
     */
    fun insertOrReplace(
        emailId: Long,
        text: String,
        downloadedAt: Long,
        html: String? = null,
        htmlRequested: Boolean = false,
    ) {
        val values = ContentValues().apply {
            put(BodiesTable.EMAIL_ID, emailId)
            put(BodiesTable.BODY_TEXT, text)
            // 三态写入：有 HTML 写内容，「问过但没有」写空串，「没问过」留 SQL NULL
            putNullable(BodiesTable.BODY_HTML, HtmlCacheState.toColumnValue(html, htmlRequested))
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
