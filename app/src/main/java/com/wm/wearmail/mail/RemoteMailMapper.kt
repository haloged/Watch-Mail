package com.wm.wearmail.mail

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.model.MailFolder

/**
 * 协议层 [RemoteMail] → 本地 [EmailMeta] 的映射（**可指定文件夹**）。
 *
 * 同步主链路（`SyncEngine`）自己的映射固定写 INBOX；这里给「垃圾邮件箱」用：
 * 同一套字段顺序，但 folder 由调用方传入（各服务商的垃圾文件夹名不同）。
 */
internal fun RemoteMail.toEmailMeta(
    accountId: Long,
    folder: String = Account.FOLDER_INBOX,
): EmailMeta = EmailMeta(
    accountId = accountId,
    folder = folder,
    uid = uid,
    messageId = messageId,
    from = from,
    to = to,
    cc = cc,
    subject = subject,
    dateMillis = dateMillis,
    isRead = isRead,
    isFlagged = isFlagged,
    hasAttachments = hasAttachments,
    sizeBytes = sizeBytes,
)

/**
 * 是否是「垃圾邮件」文件夹。
 *
 * 判定顺序：优先看 IMAP 的 SPECIAL-USE 属性（`\Junk` / `\Spam`），
 * 其次看名称（各服务商命名不一：Junk / Spam / 垃圾邮件 / 广告邮件）。
 */
internal fun MailFolder.isJunkFolder(): Boolean {
    val specialUse = attributes.any {
        it.equals("\\Junk", ignoreCase = true) || it.equals("\\Spam", ignoreCase = true)
    }
    if (specialUse) return true
    val lower = name.lowercase()
    return lower.contains("junk") || lower.contains("spam") || name.contains("垃圾") ||
        name.contains("广告")
}
