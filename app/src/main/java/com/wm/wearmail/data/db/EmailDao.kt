package com.wm.wearmail.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.MimeAddressSupport
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.model.MailAddress

/**
 * 邮件元数据表访问对象（`emails`）。
 *
 * **线程约定**：全部方法同步执行，线程调度由
 * [com.wm.wearmail.data.repo.EmailRepositoryImpl] 用 `Dispatchers.IO` 负责。
 *
 * 性能约定：批量写入必须走 [upsertAll] 的**单事务**路径，避免每条 SQL 一次 fsync
 * （手表端 eMMC 随机写性能较差，逐条提交会让同步耗时成倍增长）。
 */
class EmailDao(private val db: MailDatabase) {

    /**
     * 批量写入/更新邮件元数据。
     *
     * 去重键为 `(account_id, folder, uid)`：
     * - 新记录 → INSERT；
     * - 已存在 → 仅 UPDATE 可变字段（主题/时间/已读/星标/附件/大小），
     *   不会覆盖本地更「新」的已读状态（同步引擎已先行合并远端状态）。
     *
     * @return 新插入的条数（已存在的记录不计入，与接口契约一致）
     */
    fun upsertAll(mails: List<EmailMeta>): Int {
        if (mails.isEmpty()) return 0
        var inserted = 0
        try {
            db.transaction { d ->
                for (mail in mails) {
                    val id = d.insertWithOnConflict(
                        EmailsTable.NAME,
                        null,
                        emailValues(mail),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    if (id == -1L) {
                        // -1 表示唯一键冲突：记录已存在，改为更新可变字段
                        d.update(
                            EmailsTable.NAME,
                            updatableValues(mail),
                            "${EmailsTable.ACCOUNT_ID} = ? AND ${EmailsTable.FOLDER} = ? AND ${EmailsTable.UID} = ?",
                            arrayOf(mail.accountId.toString(), mail.folder, mail.uid.toString()),
                        )
                    } else {
                        inserted++
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.e(TAG, "批量写入邮件失败（事务已回滚）", t)
            return 0
        }
        Logs.d(TAG, "upsertAll 完成：共 ${mails.size} 条，新插入 $inserted 条")
        return inserted
    }

    /**
     * 查询统一收件箱：按时间倒序，且**只包含 folder = 'INBOX'** 的记录
     * （统一收件箱不合并已发送/草稿等其他文件夹）。
     *
     * @param accountId null 表示不过滤（全部账户合并）
     */
    fun queryInbox(accountId: Long?, limit: Int): List<EmailMeta> {
        val selection = StringBuilder("${EmailsTable.FOLDER} = ?")
        val args = mutableListOf(INBOX)
        if (accountId != null) {
            selection.append(" AND ${EmailsTable.ACCOUNT_ID} = ?")
            args.add(accountId.toString())
        }
        return db.read { d ->
            d.query(
                EmailsTable.NAME,
                PROJECTION,
                selection.toString(),
                args.toTypedArray(),
                null,
                null,
                "${EmailsTable.DATE_MILLIS} DESC, ${EmailsTable.ID} DESC",
                limit.coerceAtLeast(1).toString(),
            ).use { c -> c.mapRows(::mapEmail) }
        }
    }

    /** 未读数：accountId 为 null 时为全部账户之和 */
    fun unreadCount(accountId: Long?): Int {
        val sql = StringBuilder(
            "SELECT COUNT(*) FROM ${EmailsTable.NAME} WHERE ${EmailsTable.IS_READ} = 0",
        )
        val args = mutableListOf<String>()
        if (accountId != null) {
            sql.append(" AND ${EmailsTable.ACCOUNT_ID} = ?")
            args.add(accountId.toString())
        }
        return db.read { d ->
            d.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
    }

    /** 某账户某文件夹已同步的最大 UID（增量同步基准，0 表示尚未同步） */
    fun latestUid(accountId: Long, folder: String): Long = rawMaxUid(accountId, folder)

    /** 同 [latestUid]：用于 IDLE 推送到达后的新邮件判定 */
    fun maxUidInFolder(accountId: Long, folder: String): Long = rawMaxUid(accountId, folder)

    /** 按主键查询单封邮件（详情页使用） */
    fun queryById(id: Long): EmailMeta? = db.read { d ->
        d.query(
            EmailsTable.NAME,
            PROJECTION,
            "${EmailsTable.ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { c -> if (c.moveToFirst()) mapEmail(c) else null }
    }

    /** 标记已读/未读 */
    fun setRead(id: Long, read: Boolean) {
        val values = ContentValues().apply { put(EmailsTable.IS_READ, if (read) 1 else 0) }
        updateById(id, values)
    }

    /** 标记/取消星标 */
    fun setFlagged(id: Long, flagged: Boolean) {
        val values = ContentValues().apply { put(EmailsTable.IS_FLAGGED, if (flagged) 1 else 0) }
        updateById(id, values)
    }

    /** 删除单封邮件（正文一并删除，兼容未启用外键的环境） */
    fun delete(id: Long) {
        db.transaction { d ->
            d.delete(BodiesTable.NAME, "${BodiesTable.EMAIL_ID} = ?", arrayOf(id.toString()))
            d.delete(EmailsTable.NAME, "${EmailsTable.ID} = ?", arrayOf(id.toString()))
        }
    }

    /** 清空某账户的全部邮件与正文（删除账户时调用） */
    fun deleteByAccount(accountId: Long) {
        db.transaction { d ->
            d.delete(
                BodiesTable.NAME,
                "${BodiesTable.EMAIL_ID} IN (SELECT ${EmailsTable.ID} FROM ${EmailsTable.NAME} WHERE ${EmailsTable.ACCOUNT_ID} = ?)",
                arrayOf(accountId.toString()),
            )
            d.delete(EmailsTable.NAME, "${EmailsTable.ACCOUNT_ID} = ?", arrayOf(accountId.toString()))
        }
    }

    /** 本地邮件总数（设置页展示缓存占用） */
    fun totalCount(): Int = db.read { d ->
        d.rawQuery("SELECT COUNT(*) FROM ${EmailsTable.NAME}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 刷新最近访问时间（LRU 淘汰的排序依据） */
    fun touchAccess(id: Long, ts: Long) {
        val values = ContentValues().apply { put(EmailsTable.LAST_ACCESS_AT, ts) }
        updateById(id, values)
    }

    /**
     * LRU 淘汰邮件元数据：只保留最近访问的 [keep] 条，其余删除。
     *
     * 排序依据 `last_access_at ASC, date_millis ASC`：最久未访问、且最旧的邮件优先淘汰。
     * 正文通过外键级联删除（并额外显式删除一次兜底）。
     *
     * @return 实际删除的条数
     */
    fun evictOldestHeaders(keep: Int): Int {
        if (keep < 0) return 0
        return db.transaction { d ->
            val total = d.rawQuery("SELECT COUNT(*) FROM ${EmailsTable.NAME}", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            if (total <= keep) {
                0
            } else {
                // 先取出「最久未访问 + 最旧」的待淘汰主键，再分别删除正文与元数据
                val ids = d.query(
                    EmailsTable.NAME,
                    arrayOf(EmailsTable.ID),
                    null,
                    null,
                    null,
                    null,
                    "${EmailsTable.LAST_ACCESS_AT} ASC, ${EmailsTable.DATE_MILLIS} ASC",
                    (total - keep).toString(),
                ).use { c ->
                    val list = ArrayList<String>(total - keep)
                    while (c.moveToNext()) list.add(c.getLong(0).toString())
                    list
                }
                if (ids.isEmpty()) {
                    0
                } else {
                    val args = ids.toTypedArray()
                    val inClause = "(${placeholders(ids.size)})"
                    d.delete(BodiesTable.NAME, "${BodiesTable.EMAIL_ID} IN $inClause", args)
                    d.delete(EmailsTable.NAME, "${EmailsTable.ID} IN $inClause", args)
                }
            }
        }
    }

    /**
     * 通知角标候选：最近收到且未读的邮件（按时间倒序）。
     * 通知内容由 `notify` 包负责渲染，这里只返回元数据。
     */
    fun allForNotification(limit: Int): List<EmailMeta> = db.read { d ->
        d.query(
            EmailsTable.NAME,
            PROJECTION,
            "${EmailsTable.IS_READ} = 0",
            null,
            null,
            null,
            "${EmailsTable.DATE_MILLIS} DESC",
            limit.coerceAtLeast(1).toString(),
        ).use { c -> c.mapRows(::mapEmail) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun rawMaxUid(accountId: Long, folder: String): Long = db.read { d ->
        d.rawQuery(
            "SELECT MAX(${EmailsTable.UID}) FROM ${EmailsTable.NAME} WHERE ${EmailsTable.ACCOUNT_ID} = ? AND ${EmailsTable.FOLDER} = ?",
            arrayOf(accountId.toString(), folder),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    private fun updateById(id: Long, values: ContentValues) {
        db.write { it.update(EmailsTable.NAME, values, "${EmailsTable.ID} = ?", arrayOf(id.toString())) }
    }

    /** 插入时写入的全部列 */
    private fun emailValues(m: EmailMeta): ContentValues = ContentValues().apply {
        put(EmailsTable.ACCOUNT_ID, m.accountId)
        put(EmailsTable.FOLDER, m.folder)
        put(EmailsTable.UID, m.uid)
        putNullable(EmailsTable.MESSAGE_ID, m.messageId)
        put(EmailsTable.FROM_ADDRESS, m.from.address)
        putNullable(EmailsTable.FROM_NAME, m.from.name)
        put(EmailsTable.TO_ADDRESSES, MimeAddressSupport.formatAddressList(m.to))
        put(EmailsTable.CC_ADDRESSES, MimeAddressSupport.formatAddressList(m.cc))
        put(EmailsTable.SUBJECT, m.subject)
        put(EmailsTable.DATE_MILLIS, m.dateMillis)
        put(EmailsTable.IS_READ, if (m.isRead) 1 else 0)
        put(EmailsTable.IS_FLAGGED, if (m.isFlagged) 1 else 0)
        put(EmailsTable.HAS_ATTACHMENTS, if (m.hasAttachments) 1 else 0)
        put(EmailsTable.SIZE_BYTES, m.sizeBytes)
        put(EmailsTable.LAST_ACCESS_AT, System.currentTimeMillis())
    }

    /** 记录已存在时允许被覆盖的列（不含 uid/folder/account_id 等身份列） */
    private fun updatableValues(m: EmailMeta): ContentValues = ContentValues().apply {
        put(EmailsTable.SUBJECT, m.subject)
        put(EmailsTable.DATE_MILLIS, m.dateMillis)
        put(EmailsTable.IS_READ, if (m.isRead) 1 else 0)
        put(EmailsTable.IS_FLAGGED, if (m.isFlagged) 1 else 0)
        put(EmailsTable.HAS_ATTACHMENTS, if (m.hasAttachments) 1 else 0)
        put(EmailsTable.SIZE_BYTES, m.sizeBytes)
        put(EmailsTable.FROM_ADDRESS, m.from.address)
        putNullable(EmailsTable.FROM_NAME, m.from.name)
        put(EmailsTable.TO_ADDRESSES, MimeAddressSupport.formatAddressList(m.to))
        put(EmailsTable.CC_ADDRESSES, MimeAddressSupport.formatAddressList(m.cc))
    }

    private fun mapEmail(c: Cursor): EmailMeta = EmailMeta(
        id = c.getLong(c.getColumnIndexOrThrow(EmailsTable.ID)),
        accountId = c.getLong(c.getColumnIndexOrThrow(EmailsTable.ACCOUNT_ID)),
        folder = c.getString(c.getColumnIndexOrThrow(EmailsTable.FOLDER)).orEmpty(),
        uid = c.getLong(c.getColumnIndexOrThrow(EmailsTable.UID)),
        messageId = c.getStringOrNull(c.getColumnIndexOrThrow(EmailsTable.MESSAGE_ID)),
        from = MailAddress(
            address = c.getString(c.getColumnIndexOrThrow(EmailsTable.FROM_ADDRESS)).orEmpty(),
            name = c.getStringOrNull(c.getColumnIndexOrThrow(EmailsTable.FROM_NAME)),
        ),
        to = MimeAddressSupport.parseAddressList(c.getStringOrNull(c.getColumnIndexOrThrow(EmailsTable.TO_ADDRESSES))),
        cc = MimeAddressSupport.parseAddressList(c.getStringOrNull(c.getColumnIndexOrThrow(EmailsTable.CC_ADDRESSES))),
        subject = c.getString(c.getColumnIndexOrThrow(EmailsTable.SUBJECT)).orEmpty(),
        dateMillis = c.getLong(c.getColumnIndexOrThrow(EmailsTable.DATE_MILLIS)),
        isRead = c.getInt(c.getColumnIndexOrThrow(EmailsTable.IS_READ)) != 0,
        isFlagged = c.getInt(c.getColumnIndexOrThrow(EmailsTable.IS_FLAGGED)) != 0,
        hasAttachments = c.getInt(c.getColumnIndexOrThrow(EmailsTable.HAS_ATTACHMENTS)) != 0,
        sizeBytes = c.getLong(c.getColumnIndexOrThrow(EmailsTable.SIZE_BYTES)),
    )

    companion object {
        private const val TAG = "EmailDao"

        /** IMAP 收件箱文件夹名（与 [com.wm.wearmail.model.Account.FOLDER_INBOX] 一致） */
        private const val INBOX = "INBOX"

        private val PROJECTION: Array<String> = arrayOf(
            EmailsTable.ID,
            EmailsTable.ACCOUNT_ID,
            EmailsTable.FOLDER,
            EmailsTable.UID,
            EmailsTable.MESSAGE_ID,
            EmailsTable.FROM_ADDRESS,
            EmailsTable.FROM_NAME,
            EmailsTable.TO_ADDRESSES,
            EmailsTable.CC_ADDRESSES,
            EmailsTable.SUBJECT,
            EmailsTable.DATE_MILLIS,
            EmailsTable.IS_READ,
            EmailsTable.IS_FLAGGED,
            EmailsTable.HAS_ATTACHMENTS,
            EmailsTable.SIZE_BYTES,
        )
    }
}
