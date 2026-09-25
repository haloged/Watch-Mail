package com.haloged.watchmail.data.remote.imap

import android.util.Log
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.local.entity.EmailEntity
import com.haloged.watchmail.data.local.entity.EncryptionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import com.sun.mail.imap.IMAPStore

/**
 * IMAP同步结果
 */
sealed class ImapSyncResult {
    data class Success(val emails: List<EmailEntity>, val newCount: Int) : ImapSyncResult()
    data class Error(val message: String, val exception: Throwable? = null) : ImapSyncResult()
}

/**
 * 邮件正文下载结果
 */
sealed class BodyDownloadResult {
    data class Success(val bodyText: String, val bodyHtml: String?) : BodyDownloadResult()
    data class Error(val message: String) : BodyDownloadResult()
}

/**
 * IMAP邮件同步管理器
 * 负责与IMAP服务器通信，获取邮件元数据和正文
 *
 * ⚠ 稳定性要点：
 *  1. 所有 catch 一律吞 Throwable —— JavaMail 在 Android 上可能抛 NoClassDefFoundError、
 *     OOM 等 Error，它们不是 Exception，若漏接会从协程冒泡到主线程导致闪退
 *  2. 元数据同步阶段绝不无条件加载 message.content（会全量下载正文/附件，手表易 OOM）
 */
class ImapSyncManager {

    companion object {
        private const val TAG = "ImapSyncManager"
        private const val CONNECTION_TIMEOUT = 10000 // 10秒连接超时
        private const val READ_TIMEOUT = 30000 // 30秒读取超时
        private const val DEFAULT_SYNC_COUNT = 50 // 默认同步邮件数量
        private const val MAX_RETRY = 3 // 最大重试次数（指数退避）
        private const val BACKOFF_BASE_MS = 1000L // 退避基数：1s → 2s → 4s
    }

    /**
     * 获取解密后的密码（KeyStore + AES-GCM 密文 → 明文，仅驻留内存）
     */
    private fun getDecryptedPassword(account: AccountEntity): String {
        return try {
            com.haloged.watchmail.util.EncryptionUtil.decrypt(account.encryptedPassword)
        } catch (e: Throwable) {
            Log.e(TAG, "密码解密失败: ${account.email}", e)
            throw MessagingException("密码解密失败，账户凭据可能已损坏", e as? Exception)
        }
    }

    /**
     * 创建并认证连接 IMAP Store
     * 带指数退避自动重连，认证失败不重试（避免锁定账户）
     */
    private fun connectStore(account: AccountEntity): Store {
        var lastError: Exception? = null
        var attempt = 0
        while (attempt < MAX_RETRY) {
            try {
                val store = createStore(account)
                // 关键：必须携带用户名与解密后的密码，否则无法通过认证
                store.connect(
                    account.imapHost,
                    account.imapPort,
                    account.email,
                    getDecryptedPassword(account)
                )
                return store
            } catch (e: AuthenticationFailedException) {
                // 认证失败与网络无关，重试无意义，直接抛出
                throw e
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < MAX_RETRY) {
                    val backoff = BACKOFF_BASE_MS * (1L shl (attempt - 1))
                    Log.w(TAG, "IMAP连接失败，${backoff}ms后第${attempt}次重试: ${account.email}", e)
                    try {
                        Thread.sleep(backoff)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        throw MessagingException("IMAP连接失败（已重试${MAX_RETRY}次）", lastError)
    }

    /**
     * 测试IMAP连接
     * @param account 账户配置
     * @return 连接是否成功
     */
    suspend fun testConnection(account: AccountEntity): Boolean = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            store = connectStore(account)
            Log.d(TAG, "连接测试成功: ${account.email}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "连接测试失败: ${account.email}", e)
            false
        } finally {
            closeStore(store)
        }
    }

    /**
     * 同步邮件元数据
     * @param account 账户配置
     * @param sinceUid 从此UID开始同步（增量同步）
     * @param maxCount 最大同步数量
     * @return 同步结果
     */
    suspend fun syncEmails(
        account: AccountEntity,
        sinceUid: Long? = null,
        maxCount: Int = DEFAULT_SYNC_COUNT
    ): ImapSyncResult = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null

        try {
            Log.d(TAG, "开始同步邮件: ${account.email}, sinceUid=$sinceUid")

            store = connectStore(account)

            // 打开收件箱
            val imapStore = store as IMAPStore
            folder = imapStore.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)

            // 获取邮件列表
            val messages: Array<Message>
            val emailEntities = mutableListOf<EmailEntity>()

            val uidFolder = folder as UIDFolder
            if (sinceUid != null && sinceUid > 0) {
                // 增量同步：仅拉取 UID 大于 sinceUid 的邮件（UID 范围 FETCH，避免全文件夹扫描）
                val nextUid = sinceUid + 1
                val maxUid = uidFolder.uidNext
                Log.d(TAG, "增量同步: nextUid=$nextUid, uidNext=$maxUid")

                messages = if (nextUid < maxUid) {
                    // UID 范围获取；部分服务端对超大范围会拒绝，故限制在 maxCount 内
                    // ⚠ getMessagesByUID 可能返回 null，或数组内含 null 元素，必须过滤
                    val endUid = minOf(maxUid - 1, nextUid + maxCount - 1)
                    uidFolder.getMessagesByUID(nextUid, endUid)
                        ?.filterNotNull()
                        ?.toTypedArray()
                        ?: emptyArray()
                } else {
                    emptyArray()
                }
            } else {
                // 首次同步：获取最新的 maxCount 封邮件
                val messageCount = folder.messageCount
                val startIndex = maxOf(1, messageCount - maxCount + 1)
                messages = if (messageCount > 0) {
                    folder.getMessages(startIndex, messageCount)
                        ?.filterNotNull()
                        ?.toTypedArray()
                        ?: emptyArray()
                } else {
                    emptyArray()
                }
            }

            // 批量获取邮件元数据
            if (messages.isNotEmpty()) {
                val fetchProfile = FetchProfile()
                fetchProfile.add(FetchProfile.Item.ENVELOPE)
                fetchProfile.add(FetchProfile.Item.FLAGS)
                fetchProfile.add(UIDFolder.FetchProfileItem.UID)
                // CONTENT_INFO 只取 BODYSTRUCTURE（结构），不拉正文数据
                fetchProfile.add(FetchProfile.Item.CONTENT_INFO)

                folder.fetch(messages, fetchProfile)

                for (message in messages) {
                    try {
                        val uid = uidFolder.getUID(message)
                        val entity = messageToEntity(message, account.id, uid)
                        emailEntities.add(entity)
                    } catch (t: Throwable) {
                        // 单封解析失败不能影响整体同步
                        Log.w(TAG, "解析邮件失败（已跳过该封）", t)
                    }
                }
            }

            Log.d(TAG, "同步完成: ${account.email}, 获取${emailEntities.size}封邮件")

            // 注意：newCount 由 Repository 依据数据库中已存在的 UID 精确计算
            ImapSyncResult.Success(
                emails = emailEntities.sortedByDescending { it.receivedAt },
                newCount = emailEntities.size
            )

        } catch (e: AuthenticationFailedException) {
            Log.e(TAG, "认证失败: ${account.email}", e)
            val errorMsg = buildAuthErrorMessage(account.email)
            ImapSyncResult.Error(errorMsg, e)
        } catch (e: MessagingException) {
            Log.e(TAG, "邮件协议错误: ${account.email}", e)
            ImapSyncResult.Error(describeConnectError(e), e)
        } catch (t: Throwable) {
            // 捕获 Throwable 而非 Exception：OOM / NoClassDefFoundError 等 Error 也必须兜住
            Log.e(TAG, "同步失败: ${account.email}", t)
            ImapSyncResult.Error("同步失败: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            closeFolder(folder)
            closeStore(store)
        }
    }

    /**
     * 下载邮件正文（按需加载，仅在用户打开详情时调用）
     * @param account 账户配置
     * @param uid 邮件UID
     * @return 正文内容
     */
    suspend fun downloadBody(
        account: AccountEntity,
        uid: Long
    ): BodyDownloadResult = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null

        try {
            Log.d(TAG, "下载邮件正文: ${account.email}, uid=$uid")

            store = connectStore(account)

            val imapStore = store as IMAPStore
            folder = imapStore.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)

            val uidFolder = folder as UIDFolder
            val message = uidFolder.getMessageByUID(uid)

            if (message == null) {
                return@withContext BodyDownloadResult.Error("邮件不存在")
            }

            // 提取正文
            val bodyContent = extractBodyContent(message)

            Log.d(TAG, "正文下载成功: ${account.email}, uid=$uid")

            BodyDownloadResult.Success(
                bodyText = bodyContent.first,
                bodyHtml = bodyContent.second
            )

        } catch (t: Throwable) {
            Log.e(TAG, "正文下载失败: ${account.email}, uid=$uid", t)
            BodyDownloadResult.Error("下载失败: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            closeFolder(folder)
            closeStore(store)
        }
    }

    /**
     * 标记邮件为已读
     */
    suspend fun markAsRead(
        account: AccountEntity,
        uid: Long,
        isRead: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null

        try {
            store = connectStore(account)

            val imapStore = store as IMAPStore
            folder = imapStore.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)

            val uidFolder = folder as UIDFolder
            val message = uidFolder.getMessageByUID(uid)

            if (message != null) {
                message.setFlag(Flags.Flag.SEEN, isRead)
                Log.d(TAG, "标记已读成功: uid=$uid, isRead=$isRead")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "标记已读失败: uid=$uid", t)
            false
        } finally {
            closeFolder(folder)
            closeStore(store)
        }
    }

    /**
     * 删除邮件
     */
    suspend fun deleteEmail(
        account: AccountEntity,
        uid: Long
    ): Boolean = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null

        try {
            store = connectStore(account)

            val imapStore = store as IMAPStore
            folder = imapStore.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)

            val uidFolder = folder as UIDFolder
            val message = uidFolder.getMessageByUID(uid)

            if (message != null) {
                message.setFlag(Flags.Flag.DELETED, true)
                folder.expunge()
                Log.d(TAG, "删除邮件成功: uid=$uid")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "删除邮件失败: uid=$uid", t)
            false
        } finally {
            closeFolder(folder)
            closeStore(store)
        }
    }

    /**
     * 创建IMAP Store（不负责连接/认证）
     */
    private fun createStore(account: AccountEntity): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", account.imapHost)
            put("mail.imaps.port", account.imapPort.toString())
            put("mail.imaps.connectiontimeout", CONNECTION_TIMEOUT.toString())
            put("mail.imaps.timeout", READ_TIMEOUT.toString())
            // ⚠ 千万不要设置 mail.imaps.localaddress / mail.imaps.localhost
            //   SocketFetcher 会用 InetAddress.getByName("") 解析出 loopback(::1)，
            //   导致 socket 绑定回环地址当源地址，外网连接直接 ENETUNREACH。
            //   保持默认（不绑定），由 OS 自动选择 WiFi 出口地址。

            when (account.imapEncryption) {
                EncryptionType.SSL -> {
                    put("mail.imaps.ssl.enable", "true")
                    put("mail.imaps.ssl.trust", account.imapHost)
                }
                EncryptionType.TLS -> {
                    put("mail.imaps.ssl.enable", "true")
                    put("mail.imaps.ssl.trust", account.imapHost)
                }
                EncryptionType.STARTTLS -> {
                    put("mail.imaps.starttls.enable", "true")
                    put("mail.imaps.ssl.trust", account.imapHost)
                }
                EncryptionType.NONE -> {
                    put("mail.imaps.ssl.enable", "false")
                }
            }
        }

        val session = Session.getInstance(props)
        return session.getStore("imaps")
    }

    /**
     * 将Message转换为EmailEntity
     *
     * ⚠ 本方法在「元数据同步」阶段被批量调用（最多 50 封），
     * **绝不能无条件调用 message.content** —— 那会把整封邮件（含附件）全量下载，
     * 在手表上极易 OOM；正文一律走「点击详情时按需下载」（downloadBody）。
     */
    private fun messageToEntity(message: Message, accountId: Long, uid: Long): EmailEntity {
        val from = message.from?.firstOrNull()
        val fromAddress = (from as? InternetAddress)?.address ?: from?.toString() ?: ""
        val fromName = (from as? InternetAddress)?.personal ?: fromAddress.substringBefore("@")

        val toAddresses = message.getRecipients(Message.RecipientType.TO)
        val toAddress = toAddresses?.joinToString(", ") {
            (it as? InternetAddress)?.address ?: it.toString()
        } ?: ""

        val subject = message.subject ?: "(无主题)"
        val receivedDate = message.receivedDate?.time ?: System.currentTimeMillis()
        val isSeen = message.isSet(Flags.Flag.SEEN)
        val contentType = message.contentType?.lowercase() ?: ""

        // 预览：仅对「纯文本、非 multipart」的邮件做轻量尝试；
        // 任何 Throwable 都静默降级，绝不影响同步主流程
        val preview = if (contentType.contains("text/plain") && !contentType.contains("multipart")) {
            try {
                (message.content as? String)
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.take(100)
                    ?: ""
            } catch (t: Throwable) {
                Log.w(TAG, "预览提取失败（已忽略）", t)
                ""
            }
        } else {
            ""
        }

        // 附件判定：优先看 BODYSTRUCTURE；失败则用 contentType 粗判
        val hasAttachment = try {
            message.attachmentCount > 0
        } catch (t: Throwable) {
            contentType.contains("multipart")
        }

        return EmailEntity(
            accountId = accountId,
            uid = uid,
            fromAddress = fromAddress,
            fromName = fromName,
            toAddress = toAddress,
            subject = subject,
            preview = preview,
            receivedAt = receivedDate,
            isRead = isSeen,
            hasAttachment = hasAttachment,
            flags = message.flags.toString()
        )
    }

    /**
     * 提取邮件正文内容
     * @return Pair<纯文本, HTML文本>
     */
    private fun extractBodyContent(message: Message): Pair<String, String?> {
        var plainText: String? = null
        var htmlText: String? = null

        try {
            val content = message.content

            if (content is String) {
                // 简单文本邮件
                if (message.contentType.contains("text/html", ignoreCase = true)) {
                    htmlText = content
                    plainText = stripHtml(content)
                } else {
                    plainText = content
                }
            } else if (content is Multipart) {
                // 多部分邮件
                extractFromMultipart(content, { plainText = it }, { htmlText = it })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "提取正文失败", t)
        }

        return Pair(plainText ?: "", htmlText)
    }

    /**
     * 从Multipart中提取正文
     */
    private fun extractFromMultipart(
        multipart: Multipart,
        onPlainText: (String) -> Unit,
        onHtmlText: (String) -> Unit
    ) {
        for (i in 0 until multipart.count) {
            val bodyPart = multipart.getBodyPart(i)
            val contentType = bodyPart.contentType.lowercase()

            when {
                contentType.contains("text/plain") -> {
                    try {
                        onPlainText(bodyPart.content.toString())
                    } catch (t: Throwable) {
                        Log.w(TAG, "读取纯文本失败", t)
                    }
                }
                contentType.contains("text/html") -> {
                    try {
                        val html = bodyPart.content.toString()
                        onHtmlText(html)
                    } catch (t: Throwable) {
                        Log.w(TAG, "读取HTML失败", t)
                    }
                }
                contentType.contains("multipart/") -> {
                    try {
                        val innerMultipart = bodyPart.content as? Multipart
                        if (innerMultipart != null) {
                            extractFromMultipart(innerMultipart, onPlainText, onHtmlText)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "解析嵌套Multipart失败", t)
                    }
                }
            }
        }
    }

    /**
     * 去除HTML标签，还原为纯文本
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br[^>]*>"), "\n")
            .replace(Regex("<p[^>]*>"), "\n")
            .replace(Regex("</p>"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * 安全关闭Store
     */
    private fun closeStore(store: Store?) {
        try {
            if (store != null && store.isConnected) {
                store.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "关闭Store失败", t)
        }
    }

    /**
     * 安全关闭Folder
     */
    private fun closeFolder(folder: Folder?) {
        try {
            if (folder != null && folder.isOpen) {
                folder.close(false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "关闭Folder失败", t)
        }
    }
}

/**
 * 把底层网络异常翻译成用户可读的提示
 * 便于区分「没网 / DNS / 端口不通 / 超时」，而不是笼统的"连接失败"
 */
private fun describeConnectError(e: Throwable): String {
    var cur: Throwable? = e
    val sb = StringBuilder()
    while (cur != null) {
        sb.append(cur.message ?: "").append(' ')
        cur = cur.cause
    }
    val all = sb.toString()
    return when {
        all.contains("ENETUNREACH") || all.contains("Network is unreachable") ->
            "网络不可达：请确认手表已连接 WiFi 且能上网"
        all.contains("EHOSTUNREACH") || all.contains("No route to host") ->
            "找不到主机：网络路由异常，请检查 WiFi"
        all.contains("ENOTFOUND") || all.contains("UnknownHost") || all.contains("Unable to resolve") ->
            "域名解析失败：请检查网络或稍后重试"
        all.contains("ETIMEDOUT") || all.contains("timeout", ignoreCase = true) ->
            "连接超时：服务器无响应，可稍后重试"
        all.contains("ECONNREFUSED") || all.contains("Connection refused") ->
            "连接被拒绝：请核对服务器地址和端口"
        all.contains("SSLHandshake") || all.contains("SSL") || all.contains("certificate") ->
            "安全连接失败：请检查加密方式（SSL/STARTTLS）是否正确"
        else ->
            "邮件服务器连接失败：${e.message ?: e.javaClass.simpleName}"
    }
}

/**
 * 构建认证错误提示信息（按服务商给出针对性指引）
 */
private fun buildAuthErrorMessage(email: String): String {
    val domain = email.substringAfter("@").lowercase()
    return when {
        domain.contains("gmail") ->
            "Gmail需要使用应用专用密码\n请在Google账号设置中生成"
        domain.contains("outlook") || domain.contains("hotmail") || domain.contains("live") ->
            "Outlook需要使用应用密码\n请在Microsoft账号安全设置中生成"
        domain.contains("qq") ->
            "QQ邮箱需要使用授权码\n请在QQ邮箱设置→账户中开启IMAP并获取授权码"
        domain.contains("163") || domain.contains("126") ->
            "网易邮箱需要使用授权码\n请在邮箱设置→POP3/SMTP中开启并获取授权码"
        domain.contains("sina") ->
            "新浪邮箱需要使用授权码\n请在邮箱设置中开启IMAP并获取授权码"
        else ->
            "认证失败，请检查：\n1.密码是否正确\n2.是否需要应用专用密码\n3.IMAP/SMTP是否已开启"
    }
}

/**
 * Message扩展属性：获取附件数量
 * 基于 BODYSTRUCTURE 判断；任何 Throwable 都降级为 0，绝不抛出
 */
val Message.attachmentCount: Int
    get() {
        return try {
            if (contentType?.lowercase()?.contains("multipart") == true) {
                val multipart = content as? Multipart ?: return 0
                var count = 0
                for (i in 0 until multipart.count) {
                    val bodyPart = multipart.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(bodyPart.disposition, ignoreCase = true)) {
                        count++
                    }
                }
                count
            } else {
                0
            }
        } catch (t: Throwable) {
            0
        }
    }
