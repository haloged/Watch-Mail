package com.wm.wearmail.model

/**
 * 邮件地址（含显示名）。
 */
data class MailAddress(
    val address: String,
    val name: String? = null,
) {
    /**
     * 列表中的展示文本：
     * 有显示名时优先展示显示名，否则展示完整地址。
     */
    val display: String
        get() = name?.takeIf { it.isNotBlank() } ?: address

    companion object {
        /** 空地址占位，避免 UI 层处理 null */
        val UNKNOWN = MailAddress(address = "(未知发件人)", name = null)
    }
}

/**
 * 邮件元数据（本地缓存主模型）。
 *
 * 对应数据库表 `emails`。正文不在此结构中，按需通过
 * [com.wm.wearmail.data.repo.EmailRepository.cachedBody] 懒加载。
 */
data class EmailMeta(
    /** 本地自增主键 */
    val id: Long = 0L,
    /** 所属账户 id */
    val accountId: Long,
    /** 所在文件夹，收件箱为 "INBOX" */
    val folder: String = Account.FOLDER_INBOX,
    /** IMAP UID，账户+文件夹内唯一 */
    val uid: Long,
    /** Message-ID 头，用于跨账户去重与回复引用 */
    val messageId: String? = null,
    val from: MailAddress = MailAddress.UNKNOWN,
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val subject: String = "",
    /** 邮件时间（毫秒时间戳），缺失时退化为服务器接收时间 */
    val dateMillis: Long = 0L,
    val isRead: Boolean = false,
    val isFlagged: Boolean = false,
    val hasAttachments: Boolean = false,
    /** 邮件大小（字节），用于缓存淘汰排序 */
    val sizeBytes: Long = 0L,
) {
    /** 主题展示文本：空主题统一显示为「(无主题)」 */
    val subjectOrPlaceholder: String
        get() = subject.ifBlank { "(无主题)" }

    /** 列表次要行：主题（已去掉换行与多余空白） */
    val listSubtitle: String
        get() = subjectOrPlaceholder.replace(Regex("\\s+"), " ").trim()
}

/**
 * 邮件正文缓存（纯文本）。
 *
 * 对应数据库表 `email_bodies`。
 */
data class EmailBody(
    val emailId: Long,
    val text: String,
    val downloadedAt: Long,
)

/**
 * 本地草稿。
 *
 * 对应数据库表 `drafts`；离线时保存，联网后由同步引擎自动投递。
 *
 * @param lastError 上次发送失败原因，非空表示需要重试；为 null 表示待发送
 */
data class Draft(
    val id: Long = 0L,
    val accountId: Long,
    val to: String,
    val subject: String,
    val body: String,
    val createdAt: Long = 0L,
    val lastError: String? = null,
) {
    /** 草稿是否处于「待发送」状态（无错误或错误已被清空） */
    val isPending: Boolean
        get() = lastError == null
}

/**
 * IMAP 文件夹。
 *
 * @param delimiter 层级分隔符，Gmail 为 "/"，多数服务商为 "."；null 表示不支持层级
 * @param attributes IMAP 属性列表，例如 `\Sent`、`\Drafts`、`\Trash`
 */
data class MailFolder(
    val name: String,
    val delimiter: String? = null,
    val attributes: List<String> = emptyList(),
) {
    val isInbox: Boolean
        get() = name.equals(Account.FOLDER_INBOX, ignoreCase = true)

    /** 是否为草稿箱 */
    val isDrafts: Boolean
        get() = attributes.any { it.equals("\\Drafts", ignoreCase = true) }

    /** 是否为已发送 */
    val isSent: Boolean
        get() = attributes.any { it.equals("\\Sent", ignoreCase = true) }
}

/**
 * 常用联系人（发件人自动累积），用于缓解手表端输入困难。
 *
 * @param usedCount 使用次数，用于「常用联系人」排序
 */
data class Contact(
    val id: Long = 0L,
    val name: String? = null,
    val address: String,
    val usedCount: Int = 0,
    val lastUsedAt: Long = 0L,
) {
    val display: String
        get() = name?.takeIf { it.isNotBlank() } ?: address
}
