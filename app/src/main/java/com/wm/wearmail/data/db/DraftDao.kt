package com.wm.wearmail.data.db

import android.content.ContentValues
import android.database.Cursor
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Draft

/**
 * 草稿表访问对象（`drafts`）。
 *
 * **线程约定**：全部方法同步执行，由
 * [com.wm.wearmail.data.repo.DraftRepositoryImpl] 用 `Dispatchers.IO` 包裹。
 *
 * 数据约定：`to_addresses` 按需求存**用户原始输入字符串**（可能含多人、含显示名，
 * 甚至暂时不合法），发送时再由 SMTP 层解析，避免保存阶段因解析失败丢内容。
 */
class DraftDao(private val db: MailDatabase) {

    /**
     * 新增或更新草稿。
     *
     * `id > 0` 且记录存在 → UPDATE；否则 INSERT。
     * `last_error` 以传入 [Draft.lastError] 为准：UI 更新草稿时通常会把原对象
     * 的 `lastError` 一起带回来，因此错误状态不会被静默清空；
     * 若要主动清除错误，请调用 [markError]\(id, null\)。
     *
     * @return 草稿 id（新增时为自增 id）
     */
    fun insertOrUpdate(draft: Draft): Long {
        val values = draftValues(draft)
        if (draft.id > 0L) {
            val rows = db.write {
                it.update(DraftsTable.NAME, values, "${DraftsTable.ID} = ?", arrayOf(draft.id.toString()))
            }
            if (rows > 0) return draft.id
            Logs.w(TAG, "更新草稿 id=${draft.id} 未命中记录，转为新增")
        }
        return db.write { it.insert(DraftsTable.NAME, null, values) }
    }

    /** 全部草稿，按创建时间倒序（草稿箱列表） */
    fun queryAll(): List<Draft> = db.read { d ->
        d.query(DraftsTable.NAME, PROJECTION, null, null, null, null, "${DraftsTable.CREATED_AT} DESC")
            .use { c -> c.mapRows(::mapDraft) }
    }

    /** 待发送草稿（`last_error IS NULL`），按创建时间升序保证先存先发 */
    fun queryPending(): List<Draft> = db.read { d ->
        d.query(
            DraftsTable.NAME,
            PROJECTION,
            "${DraftsTable.LAST_ERROR} IS NULL",
            null,
            null,
            null,
            "${DraftsTable.CREATED_AT} ASC",
        ).use { c -> c.mapRows(::mapDraft) }
    }

    /** 按主键查询草稿 */
    fun queryById(id: Long): Draft? = db.read { d ->
        d.query(DraftsTable.NAME, PROJECTION, "${DraftsTable.ID} = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) mapDraft(c) else null }
    }

    /** 删除草稿（发送成功后调用） */
    fun delete(id: Long) {
        db.write { it.delete(DraftsTable.NAME, "${DraftsTable.ID} = ?", arrayOf(id.toString())) }
    }

    /** 记录/清除发送失败原因（null 表示清除错误，重新进入待发送队列） */
    fun markError(id: Long, error: String?) {
        val values = ContentValues().apply { putNullable(DraftsTable.LAST_ERROR, error) }
        db.write { it.update(DraftsTable.NAME, values, "${DraftsTable.ID} = ?", arrayOf(id.toString())) }
    }

    /** 清空某账户的全部草稿（删除账户时调用） */
    fun deleteByAccount(accountId: Long) {
        db.write { it.delete(DraftsTable.NAME, "${DraftsTable.ACCOUNT_ID} = ?", arrayOf(accountId.toString())) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun draftValues(d: Draft): ContentValues = ContentValues().apply {
        put(DraftsTable.ACCOUNT_ID, d.accountId)
        put(DraftsTable.TO_ADDRESSES, d.to)
        put(DraftsTable.SUBJECT, d.subject)
        put(DraftsTable.BODY, d.body)
        put(DraftsTable.CREATED_AT, if (d.createdAt > 0L) d.createdAt else System.currentTimeMillis())
        putNullable(DraftsTable.LAST_ERROR, d.lastError)
    }

    private fun mapDraft(c: Cursor): Draft = Draft(
        id = c.getLong(c.getColumnIndexOrThrow(DraftsTable.ID)),
        accountId = c.getLong(c.getColumnIndexOrThrow(DraftsTable.ACCOUNT_ID)),
        to = c.getString(c.getColumnIndexOrThrow(DraftsTable.TO_ADDRESSES)).orEmpty(),
        subject = c.getString(c.getColumnIndexOrThrow(DraftsTable.SUBJECT)).orEmpty(),
        body = c.getString(c.getColumnIndexOrThrow(DraftsTable.BODY)).orEmpty(),
        createdAt = c.getLong(c.getColumnIndexOrThrow(DraftsTable.CREATED_AT)),
        lastError = c.getStringOrNull(c.getColumnIndexOrThrow(DraftsTable.LAST_ERROR)),
    )

    companion object {
        private const val TAG = "DraftDao"

        private val PROJECTION: Array<String> = arrayOf(
            DraftsTable.ID,
            DraftsTable.ACCOUNT_ID,
            DraftsTable.TO_ADDRESSES,
            DraftsTable.SUBJECT,
            DraftsTable.BODY,
            DraftsTable.CREATED_AT,
            DraftsTable.LAST_ERROR,
        )
    }
}
