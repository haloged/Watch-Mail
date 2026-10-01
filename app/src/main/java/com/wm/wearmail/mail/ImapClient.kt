package com.wm.wearmail.mail

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.MailAddress
import com.wm.wearmail.model.MailFolder

/**
 * IMAP 拉取到的单封邮件元数据（尚未绑定本地账户/文件夹）。
 *
 * 与 [com.wm.wearmail.model.EmailMeta] 的区别：
 * - 本类由协议层产出，不含本地主键与账户 id，也没有「已缓存正文」语义；
 * - 仓储层负责补齐 accountId / folder 后落库。
 */
data class RemoteMail(
    /** IMAP UID，同一文件夹内单调递增（用于增量同步） */
    val uid: Long,
    val messageId: String? = null,
    val from: MailAddress = MailAddress.UNKNOWN,
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val subject: String = "",
    val dateMillis: Long = 0L,
    val isRead: Boolean = false,
    val isFlagged: Boolean = false,
    val hasAttachments: Boolean = false,
    val sizeBytes: Long = 0L,
)

/**
 * IMAP 收件客户端。
 *
 * 实现要求（见 docs/ARCHITECTURE.md）：
 * - **同步**接口一律为挂起函数，内部在 [kotlinx.coroutines.Dispatchers.IO] 执行；
 * - 连接超时 10 秒（[DEFAULT_TIMEOUT_MILLIS]），读写超时同值；
 * - 每次调用自行建立/关闭连接（无状态），避免长连接在手表上被系统回收后状态错乱；
 * - [idle] 为长连接推送，内部须实现「断线指数退避重连」，由 [shouldStop] 决定退出；
 * - 所有失败必须返回 `Result.failure(MailError)`，严禁抛出未包装异常。
 */
interface ImapClient {

    /** 测试 IMAP 连接与认证（不拉取任何邮件），用于添加账户时的「验证」按钮 */
    suspend fun testConnection(
        account: Account,
        secrets: AccountSecrets,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Result<Unit>

    /** 列出服务器文件夹 */
    suspend fun listFolders(
        account: Account,
        secrets: AccountSecrets,
    ): Result<List<MailFolder>>

    /**
     * 首次同步：拉取指定文件夹中「最新」的 [limit] 封邮件元数据。
     *
     * 实现约定：只拉取头部（From/To/Subject/Date/Flags/Size），
     * **绝不**下载正文，以满足「首次同步仅元数据」的性能要求。
     */
    suspend fun fetchRecent(
        account: Account,
        secrets: AccountSecrets,
        folder: String = Account.FOLDER_INBOX,
        limit: Int = DEFAULT_RECENT_LIMIT,
    ): Result<List<RemoteMail>>

    /**
     * 增量同步：优先使用 UID 策略拉取 `UID > lastUid` 的邮件；
     * 若 [lastUid] <= 0，则退化为 `SINCE sinceMillis` 条件（服务端 UID 不连续场景）。
     */
    suspend fun fetchNewerThan(
        account: Account,
        secrets: AccountSecrets,
        folder: String = Account.FOLDER_INBOX,
        lastUid: Long,
        sinceMillis: Long? = null,
        limit: Int = DEFAULT_INCREMENTAL_LIMIT,
    ): Result<List<RemoteMail>>

    /**
     * 按需下载单封邮件正文，返回**纯文本**。
     *
     * HTML 邮件必须剥离标签（见 `HtmlTextExtractor`），附件不下载。
     * 返回文本长度上限 [BODY_MAX_CHARS]，超出截断并追加提示。
     */
    suspend fun fetchBody(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
    ): Result<String>

    /** 设置/清除 `\Seen` 标记 */
    suspend fun setSeen(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
        seen: Boolean,
    ): Result<Unit>

    /** 设置/清除 `\Flagged`（星标）标记 */
    suspend fun setFlagged(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
        flagged: Boolean,
    ): Result<Unit>

    /** 删除邮件：优先移动到 `\Trash`，不支持时退回打 `\Deleted` + EXPUNGE */
    suspend fun deleteMessage(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
    ): Result<Unit>

    /**
     * IDLE 长连接推送。
     *
     * @param onNewMail 收到「有新邮件」通知时回调，参数为服务器报告的最新 UID
     * @param shouldStop 由调用方提供的停止条件，返回 true 时函数应尽快返回
     */
    suspend fun idle(
        account: Account,
        secrets: AccountSecrets,
        folder: String = Account.FOLDER_INBOX,
        onNewMail: suspend (Long) -> Unit,
        shouldStop: () -> Boolean,
    ): Result<Unit>

    /** 释放资源（关闭连接池/线程池），应用退出时调用 */
    fun close()

    companion object {
        /** 连接与读写超时：需求规定 10 秒 */
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000

        /** 首次同步拉取邮件数量上限：需求规定 50 封 */
        const val DEFAULT_RECENT_LIMIT: Int = 50

        /** 单次增量同步上限，防止长时间离线后一次性拉取过多 */
        const val DEFAULT_INCREMENTAL_LIMIT: Int = 100

        /** 正文最大字符数，避免超大邮件撑爆手表内存 */
        const val BODY_MAX_CHARS: Int = 200_000
    }
}
