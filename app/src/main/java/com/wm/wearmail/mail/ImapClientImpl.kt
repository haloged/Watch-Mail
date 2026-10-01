package com.wm.wearmail.mail

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailAddress
import com.wm.wearmail.model.MailFolder
import com.wm.wearmail.model.MailSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * 把底层异常归一化为 [MailError]。
 *
 * JavaMail 的 `AuthenticationFailedException` 必须显式包装成
 * [AuthenticationFailedExceptionWrapper]，否则在部分服务商的报文里
 * （例如仅返回 `[AUTHENTICATIONFAILED] Invalid credentials`）可能被误判为 Unknown，
 * 导致上层「提示重新登录」的引导失效。
 */
internal fun Throwable.toMailFailure(defaultMessage: String = "邮件操作失败"): MailError =
    when (this) {
        is AuthenticationFailedException ->
            AuthenticationFailedExceptionWrapper(message.orEmpty(), this).toMailError(defaultMessage)

        else -> toMailError(defaultMessage)
    }

/**
 * IMAP 客户端实现（JavaMail / android-mail 1.6.7）。
 *
 * 设计要点：
 * - **无状态**：每个方法自带一次「连接 → 操作 → 关闭」，避免手表被系统冻结后
 *   残留半开连接导致后续所有操作都在用一条死连接；
 * - 全部方法在 [Dispatchers.IO] 执行，JavaMail 是阻塞式 API，不能占用主线程；
 * - 所有失败都以 `Result.failure(MailError)` 返回，绝不向上抛未包装异常；
 * - 日志只记录主机、端口、UID、数量等非敏感信息，**不记录密码 / token / 正文**；
 * - 「首次同步仅元数据」是核心性能要求，因此拉取列表时只预取信封 / 标记 / UID / 大小 /
 *   Message-ID，正文一律按需单独下载。
 */
class ImapClientImpl : ImapClient {

    // ------------------------------------------------------------------
    // 连接测试 / 文件夹
    // ------------------------------------------------------------------

    override suspend fun testConnection(
        account: Account,
        secrets: AccountSecrets,
        timeoutMillis: Int,
    ): Result<Unit> =
        // 只 connect + 打开收件箱（只读）再关闭，不拉取任何邮件
        withFolder(account, secrets, Account.FOLDER_INBOX, Folder.READ_ONLY, timeoutMillis) { }

    override suspend fun listFolders(
        account: Account,
        secrets: AccountSecrets,
    ): Result<List<MailFolder>> = withStore(account, secrets) { store ->
        val listed = runCatching { store.defaultFolder.list("*") }.getOrNull().orEmpty()

        // 分隔符从根文件夹取一次即可：IMAP 中同一命名空间内分隔符一致，
        // 逐个子文件夹调用 getSeparator() 在分隔符未知时每个都会多一次 LIST 往返。
        val delimiter = runCatching { store.defaultFolder.separator }.getOrNull()
            ?.takeIf { it != '\u0000' && it != UNKNOWN_SEPARATOR }
            ?.toString()

        Logs.d(TAG, "列出文件夹：host=${account.imapHost} count=${listed.size} delimiter=$delimiter")
        listed.mapNotNull { it.toMailFolder(delimiter) }
    }

    // ------------------------------------------------------------------
    // 元数据拉取
    // ------------------------------------------------------------------

    override suspend fun fetchRecent(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        limit: Int,
    ): Result<List<RemoteMail>> =
        withFolder(account, secrets, folder, Folder.READ_ONLY) { target ->
            val count = target.messageCount
            val effectiveLimit = limit.coerceAtLeast(1)
            if (count <= 0) {
                Logs.d(TAG, "文件夹为空：folder=$folder")
                emptyList()
            } else {
                val start = maxOf(1, count - effectiveLimit + 1)
                Logs.d(TAG, "拉取最新邮件头：folder=$folder range=$start..$count")
                mapMessages(target, target.getMessages(start, count))
            }
        }

    override suspend fun fetchNewerThan(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        lastUid: Long,
        sinceMillis: Long?,
        limit: Int,
    ): Result<List<RemoteMail>> =
        withFolder(account, secrets, folder, Folder.READ_ONLY) { target ->
            val uidFolder = target as? UIDFolder
                ?: throw MailError.Protocol("该服务器不支持 UID 增量同步")

            val messages: Array<Message> = when {
                // 首选 UID 策略：UID 在同一文件夹内单调递增，是唯一可靠的增量基准
                lastUid > 0L -> {
                    Logs.d(TAG, "增量同步（UID）：folder=$folder from=${lastUid + 1}")
                    uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
                }
                // UID 基准缺失（首次同步或服务端 UID 不连续）时退化为日期搜索
                sinceMillis != null && sinceMillis > 0L -> {
                    Logs.d(TAG, "增量同步（日期）：folder=$folder since=$sinceMillis")
                    target.search(ReceivedDateTerm(ComparisonTerm.GT, Date(sinceMillis)))
                }

                else -> emptyArray()
            }

            val effectiveLimit = limit.coerceAtLeast(1)
            // 按 UID 升序返回前 limit 条：剩余部分留给下一轮增量，保证不丢邮件
            mapMessages(target, messages)
                .filter { it.uid > lastUid }
                .sortedBy { it.uid }
                .take(effectiveLimit)
        }

    // ------------------------------------------------------------------
    // 正文
    // ------------------------------------------------------------------

    override suspend fun fetchBody(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
    ): Result<String> =
        withFolder(account, secrets, folder, Folder.READ_ONLY) { target ->
            val uidFolder = target as? UIDFolder
                ?: throw MailError.Protocol("该服务器不支持 UID 定位邮件")
            val message = uidFolder.getMessageByUID(uid)
                ?: throw MailError.Protocol("邮件不存在或已被删除（uid=$uid）")

            val text = extractPlainText(message)
            if (text.length > ImapClient.BODY_MAX_CHARS) {
                // 只记长度，绝不记正文内容
                Logs.w(TAG, "正文过长已截断：uid=$uid length=${text.length}")
                text.take(ImapClient.BODY_MAX_CHARS) + TRUNCATED_SUFFIX
            } else {
                text
            }
        }

    // ------------------------------------------------------------------
    // 标记 / 删除
    // ------------------------------------------------------------------

    override suspend fun setSeen(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
        seen: Boolean,
    ): Result<Unit> = setFlag(account, secrets, folder, uid, Flags.Flag.SEEN, seen)

    override suspend fun setFlagged(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
        flagged: Boolean,
    ): Result<Unit> = setFlag(account, secrets, folder, uid, Flags.Flag.FLAGGED, flagged)

    override suspend fun deleteMessage(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        uid: Long,
    ): Result<Unit> = withStore(account, secrets) { store ->
        val target = store.getFolder(folder)
        var expunged = false
        try {
            target.open(Folder.READ_WRITE)
            val uidFolder = target as? UIDFolder
                ?: throw MailError.Protocol("该服务器不支持 UID 定位邮件")
            val message = uidFolder.getMessageByUID(uid)
                ?: throw MailError.Protocol("邮件不存在或已被删除（uid=$uid）")

            // 优先移动语义：先复制到回收站，再在原文件夹打 \Deleted
            val trash = findTrashFolder(store, folder)
            if (trash != null) {
                Logs.i(TAG, "删除邮件：先复制到回收站 folder=${trash.fullName} uid=$uid")
                try {
                    target.copyMessages(arrayOf(message), trash)
                } catch (t: Throwable) {
                    // 复制失败不阻塞删除（部分服务器禁止跨命名空间 COPY）
                    Logs.w(TAG, "复制到回收站失败，退化为直接删除", t)
                }
            } else {
                Logs.i(TAG, "服务器未提供回收站，直接打 \\Deleted 并 expunge：uid=$uid")
            }

            message.setFlag(Flags.Flag.DELETED, true)
            // IMAP 语义：READ_WRITE 下 close(true) 才会真正 EXPUNGE，删除才对其他客户端可见
            target.close(true)
            expunged = true
        } finally {
            if (!expunged) runCatching { target.close(false) }
        }
    }

    // ------------------------------------------------------------------
    // IDLE 推送
    // ------------------------------------------------------------------

    override suspend fun idle(
        account: Account,
        secrets: AccountSecrets,
        folder: String,
        onNewMail: suspend (Long) -> Unit,
        shouldStop: () -> Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val backoff = BackoffPolicy()
        var attempt = 0

        while (!shouldStop()) {
            var store: Store? = null
            var openFolder: Folder? = null
            var failure: Throwable? = null
            var sessionStart = 0L

            try {
                val session = Session.getInstance(propertiesFor(account, ImapClient.DEFAULT_TIMEOUT_MILLIS))
                val newStore = session.getStore(imapProtocolOf(account.imapSecurity))
                newStore.connect(
                    account.imapHost,
                    account.imapPort,
                    account.email,
                    credentialFor(account, secrets),
                )
                store = newStore

                val target = newStore.getFolder(folder)
                target.open(Folder.READ_ONLY)
                openFolder = target

                val imapFolder = target as? IMAPFolder
                    ?: throw MailError.Protocol("该服务器不支持 IDLE 推送")

                // 能力查询在进入 IDLE 之前做：JavaMail 的 Store 会复用已打开的连接
                val supportsIdle = (newStore as? IMAPStore)
                    ?.let { runCatching { it.hasCapability("IDLE") }.getOrDefault(false) } == true

                Logs.i(TAG, "推送会话开始：host=${account.imapHost} folder=$folder idle=$supportsIdle")
                sessionStart = System.currentTimeMillis()
                failure = pushSession(imapFolder, supportsIdle, onNewMail, shouldStop)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failure = t
            } finally {
                // 无论何种原因结束，都必须释放连接（close 同时会中止阻塞中的 IDLE）
                runCatching { openFolder?.close(false) }
                runCatching { store?.close() }
            }

            if (shouldStop()) break

            // 会话曾稳定运行过一段时间，说明配置与网络都没问题，退避计数归零
            if (sessionStart > 0L && System.currentTimeMillis() - sessionStart >= STABLE_SESSION_MILLIS) {
                attempt = 0
            }

            val waitMillis = backoff.delayMillisFor(attempt)
            if (failure != null) {
                attempt++
                Logs.w(TAG, "推送会话中断（连续失败 $attempt 次），${waitMillis}ms 后重连", failure.toMailFailure())
            } else {
                Logs.d(TAG, "推送会话结束，${waitMillis}ms 后重新建立连接")
            }
            // 分片等待：等待期间仍能响应 shouldStop()，避免最长 60 秒才退出
            waitSlices(waitMillis, shouldStop)
        }

        Result.success(Unit)
    }

    override fun close() {
        // 无状态实现没有连接池需要释放；保留该入口以便将来引入连接复用
        Logs.d(TAG, "ImapClientImpl.close()：无状态实现，无需释放资源")
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /** 建立 Store、执行 [block]、必定关闭连接；所有异常统一包装为 [MailError] */
    private suspend fun <T> withStore(
        account: Account,
        secrets: AccountSecrets,
        timeoutMillis: Int = ImapClient.DEFAULT_TIMEOUT_MILLIS,
        block: (Store) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val protocol = imapProtocolOf(account.imapSecurity)
        try {
            val session = Session.getInstance(propertiesFor(account, timeoutMillis))
            val store = session.getStore(protocol)
            store.connect(
                account.imapHost,
                account.imapPort,
                account.email,
                credentialFor(account, secrets),
            )
            Logs.d(TAG, "IMAP 已连接：host=${account.imapHost} port=${account.imapPort} protocol=$protocol")
            try {
                Result.success(block(store))
            } finally {
                runCatching { store.close() }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.e(
                TAG,
                "IMAP 操作失败：host=${account.imapHost} port=${account.imapPort} protocol=$protocol",
                t,
            )
            Result.failure(t.toMailFailure())
        }
    }

    /** 打开指定文件夹执行 [block]，结束时以 `close(false)` 释放（不触发 EXPUNGE） */
    private suspend fun <T> withFolder(
        account: Account,
        secrets: AccountSecrets,
        folderName: String,
        mode: Int,
        timeoutMillis: Int = ImapClient.DEFAULT_TIMEOUT_MILLIS,
        block: (Folder) -> T,
    ): Result<T> = withStore(account, secrets, timeoutMillis) { store ->
        val folder = store.getFolder(folderName)
        folder.open(mode)
        try {
            block(folder)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** 设置 / 清除单个 IMAP 标记 */
    private suspend fun setFlag(
        account: Account,
        secrets: AccountSecrets,
        folderName: String,
        uid: Long,
        flag: Flags.Flag,
        value: Boolean,
    ): Result<Unit> = withFolder(account, secrets, folderName, Folder.READ_WRITE) { target ->
        val uidFolder = target as? UIDFolder
            ?: throw MailError.Protocol("该服务器不支持 UID 定位邮件")
        val message = uidFolder.getMessageByUID(uid)
            ?: throw MailError.Protocol("邮件不存在或已被删除（uid=$uid）")
        message.setFlag(flag, value)
        Logs.d(TAG, "标记已提交：folder=$folderName uid=$uid flag=$flag value=$value")
        // READ_WRITE 打开 + close(false)：标记立即生效且不做 EXPUNGE（删除由 deleteMessage 负责）
    }

    // ------------------------------------------------------------------
    // 会话参数
    // ------------------------------------------------------------------

    /** `imaps` = 全程 TLS（993）；`imap` = 明文端口（143，可 STARTTLS 升级） */
    private fun imapProtocolOf(security: MailSecurity): String =
        if (security == MailSecurity.SSL_TLS) "imaps" else "imap"

    /**
     * 登录凭据。
     *
     * SASL XOAUTH2 要求把 access token 放在"密码"位置 —— JavaMail 会据此拼出
     * `user=<邮箱>^Aauth=Bearer <token>^A^A`（机制名见 [propertiesFor]）。
     *
     * [AccountSecrets.authSecret] 的两条来源：
     * 1. OAuth2 授权得到的 access token（推荐，可自动刷新）；
     * 2. 用户手动粘贴到「密码/授权码」框里的令牌（兼容旧路径，不会自动刷新）。
     */
    private fun credentialFor(account: Account, secrets: AccountSecrets): String =
        when (account.authType) {
            AuthType.OAUTH2 -> secrets.authSecret
            else -> secrets.password
        }

    private fun propertiesFor(account: Account, timeoutMillis: Int): Properties {
        // 避免调用方传 0 被 JavaMail 解释为「永不超时」，从而在弱网下永久挂起
        val timeout = timeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        val protocol = imapProtocolOf(account.imapSecurity)

        return Properties().apply {
            setProperty("mail.store.protocol", protocol)
            // 两种协议的加密参数都写上，实际生效的取决于上面的协议
            setProperty("mail.imaps.ssl.enable", "true")
            setProperty("mail.imap.starttls.enable", "true")
            setProperty("mail.imap.starttls.required", "true")
            if (account.imapSecurity == MailSecurity.NONE) {
                // 企业内网自建服务器可能完全不加密，此时不能强制 STARTTLS
                setProperty("mail.imap.starttls.enable", "false")
                setProperty("mail.imap.starttls.required", "false")
            }
            // 超时统一 10 秒（可被调用方覆盖），避免手表在弱网下长时间卡住
            setProperty("mail.imap.connectiontimeout", timeout.toString())
            setProperty("mail.imap.timeout", timeout.toString())
            setProperty("mail.imaps.connectiontimeout", timeout.toString())
            setProperty("mail.imaps.timeout", timeout.toString())
            // 读取正文时不隐式打上 \Seen（用 BODY.PEEK）：已读状态只由 setSeen 显式控制
            setProperty("mail.imap.peek", "true")
            setProperty("mail.imaps.peek", "true")
            if (account.authType == AuthType.OAUTH2) {
                setProperty("mail.imap.auth.mechanisms", "XOAUTH2")
                setProperty("mail.imaps.auth.mechanisms", "XOAUTH2")
            }
        }
    }

    // ------------------------------------------------------------------
    // 元数据映射
    // ------------------------------------------------------------------

    /** 文件夹实体 → 模型（读取失败返回 null，由调用方跳过） */
    private fun Folder.toMailFolder(delimiter: String?): MailFolder? = runCatching {
        // IMAP 专有的 \Sent / \Drafts / \Trash 属性只在 IMAPFolder 上暴露（LazyFolder 无此能力）
        val attributes = (this as? IMAPFolder)?.attributes?.toList().orEmpty()
        MailFolder(name = name, delimiter = delimiter, attributes = attributes)
    }.onFailure {
        Logs.w(TAG, "跳过无法读取的文件夹", it)
    }.getOrNull()

    /**
     * 批量映射元数据。
     *
     * 先做一次 [FetchProfile] 预取，把「信封 + 标记 + UID + 大小 + Message-ID」
     * 合并到一条 FETCH 里，避免逐封懒加载导致 50 次往返（手表端功耗敏感）。
     * 预取失败不影响正确性：后续访问会自动退化为逐条拉取。
     */
    private fun mapMessages(folder: Folder, messages: Array<Message>): List<RemoteMail> {
        if (messages.isEmpty()) return emptyList()
        prefetchHeaders(folder, messages)

        val uidFolder = folder as? UIDFolder
        return messages.mapNotNull { message ->
            // 这里的 runCatching 只包住同步的 JavaMail 调用，不会吞掉协程取消信号
            runCatching { message.toRemoteMail(uidFolder) }
                .onFailure { Logs.w(TAG, "读取邮件头失败，已跳过该封", it) }
                .getOrNull()
        }
    }

    private fun prefetchHeaders(folder: Folder, messages: Array<Message>) {
        val profile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE) // From / To / Cc / Subject / Date
            add(FetchProfile.Item.FLAGS) // \Seen / \Flagged
            add(UIDFolder.FetchProfileItem.UID) // UID：增量基准与本地唯一键
            add(FetchProfile.Item.SIZE) // RFC822.SIZE
            // 字符串条目在 IMAP 下会生成 BODY.PEEK[HEADER.FIELDS (Message-ID)]，仍属「只读头部」
            add("Message-ID")
        }
        try {
            folder.fetch(messages, profile)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.w(TAG, "预取邮件头失败，退化为逐条读取", t)
        }
    }

    /** 单封邮件 → [RemoteMail]；取不到 UID（无法落库/增量）时返回 null */
    private fun Message.toRemoteMail(uidFolder: UIDFolder?): RemoteMail? {
        val uid = uidFolder?.let { runCatching { it.getUID(this) }.getOrNull() } ?: 0L
        if (uid <= 0L) {
            Logs.w(TAG, "无法读取 UID，跳过该封邮件")
            return null
        }

        val messageFlags = runCatching { flags }.getOrNull()
        val fromAddress = runCatching { firstAddressOf(from) }.getOrNull()
            ?: MimeAddressSupport.firstAddress(rawHeader("From"))
        val toAddresses = runCatching { addressListOf(getRecipients(Message.RecipientType.TO)) }.getOrNull()
            .orEmpty()
            .ifEmpty { MimeAddressSupport.parseAddressList(rawHeader("To")) }
        val ccAddresses = runCatching { addressListOf(getRecipients(Message.RecipientType.CC)) }.getOrNull()
            .orEmpty()
            .ifEmpty { MimeAddressSupport.parseAddressList(rawHeader("Cc")) }

        val sent = runCatching { sentDate }.getOrNull()
        val received = runCatching { receivedDate }.getOrNull()
        val mimeType = runCatching { contentType }.getOrNull().orEmpty()

        return RemoteMail(
            uid = uid,
            messageId = rawHeader("Message-ID")?.trim()?.takeIf { it.isNotEmpty() },
            from = fromAddress,
            to = toAddresses,
            cc = ccAddresses,
            subject = runCatching { subject }.getOrNull().orEmpty(),
            dateMillis = sent?.time ?: received?.time ?: 0L,
            isRead = messageFlags?.contains(Flags.Flag.SEEN) == true,
            isFlagged = messageFlags?.contains(Flags.Flag.FLAGGED) == true,
            // 启发式判断：为了严格「不下载正文」，只能看 Content-Type 是否为 multipart/mixed。
            // 代价是：multipart/related（内嵌图片）或把附件放在 alternative 里的邮件可能漏判，
            // 换来的是列表同步不产生任何正文流量。
            hasAttachments = mimeType.startsWith("multipart/mixed", ignoreCase = true),
            sizeBytes = runCatching { size }.getOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L,
        )
    }

    /** 读取原始头部文本（多值用逗号连接）；失败返回 null */
    private fun Message.rawHeader(name: String): String? =
        try {
            getHeader(name)
                ?.filterNotNull()
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.d(TAG, "读取头部 $name 失败：${t.message}")
            null
        }

    private fun addressListOf(addresses: Array<Address>?): List<MailAddress> =
        addresses.orEmpty().mapNotNull { it.toMailAddressOrNull() }

    private fun firstAddressOf(addresses: Array<Address>?): MailAddress? =
        addresses.orEmpty().firstNotNullOfOrNull { it.toMailAddressOrNull() }

    /**
     * `javax.mail.Address` → [MailAddress]。
     *
     * [InternetAddress.getPersonal] 已经完成 RFC 2047 解码，因此中文显示名无需再处理；
     * 其它实现退化为字符串解析。
     */
    private fun Address.toMailAddressOrNull(): MailAddress? {
        if (this is InternetAddress) {
            val addressText = address?.trim().orEmpty()
            if (addressText.isEmpty()) return null
            val displayName = personal?.trim().orEmpty()
            return MailAddress(address = addressText, name = displayName.ifBlank { null })
        }
        val parsed = MimeAddressSupport.parseSingle(toString())
        return parsed.takeIf { it != MailAddress.UNKNOWN }
    }

    // ------------------------------------------------------------------
    // 正文抽取
    // ------------------------------------------------------------------

    /** 抽取纯文本正文：优先 text/plain，缺失时才把 text/html 降级为纯文本 */
    private fun extractPlainText(part: Part): String {
        val (plain, html) = collectText(part, depth = 0)
        return if (plain.isNotBlank()) plain.trim() else HtmlTextExtractor.toPlainText(html).trim()
    }

    /**
     * 递归收集 `text/plain` 与 `text/html` 内容。
     *
     * 附件一律不读取（附件在手表端既不展示也不缓存，读取只会浪费流量与内存）。
     * 返回值为 `(纯文本, HTML)` 二元组。
     */
    private fun collectText(part: Part, depth: Int): Pair<String, String> {
        if (depth > MAX_MIME_DEPTH) {
            Logs.w(TAG, "MIME 嵌套超过 $MAX_MIME_DEPTH 层，停止解析")
            return "" to ""
        }
        return try {
            when {
                part.isMimeType("text/plain") -> (part.content as? String).orEmpty() to ""

                part.isMimeType("text/html") -> "" to (part.content as? String).orEmpty()

                part.isMimeType("multipart/*") -> {
                    val multipart = part.content as? Multipart
                    if (multipart == null) {
                        "" to ""
                    } else {
                        val plain = StringBuilder()
                        val html = StringBuilder()
                        for (index in 0 until multipart.count) {
                            val child = multipart.getBodyPart(index)
                            if (child.isAttachmentPart()) {
                                // 不读取附件内容（不打印文件名，避免日志泄露）
                                Logs.d(TAG, "跳过附件部件（不下载内容）")
                                continue
                            }
                            val (childPlain, childHtml) = collectText(child, depth + 1)
                            if (childPlain.isNotBlank()) {
                                if (plain.isNotEmpty()) plain.append('\n')
                                plain.append(childPlain)
                            }
                            if (childHtml.isNotBlank()) {
                                if (html.isNotEmpty()) html.append('\n')
                                html.append(childHtml)
                            }
                        }
                        plain.toString() to html.toString()
                    }
                }

                else -> {
                    // 少见的非标准部件：按 JavaMail 的建议先做 RFC 2047 解码再转字符串
                    val raw = part.content as? String
                    if (raw.isNullOrEmpty()) "" to "" else MimeUtility.decodeText(raw) to ""
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.w(TAG, "解析 MIME 部件失败，已跳过", t)
            "" to ""
        }
    }

    /** 是否为附件（有附件名或 disposition=attachment，且不是 inline 内嵌） */
    private fun Part.isAttachmentPart(): Boolean {
        val disposition = runCatching { this.disposition }.getOrNull()
        if (disposition != null && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) return true
        if (disposition != null && disposition.equals(Part.INLINE, ignoreCase = true)) return false
        return runCatching { fileName }.getOrNull()?.isNotBlank() == true
    }

    // ------------------------------------------------------------------
    // IDLE 会话
    // ------------------------------------------------------------------

    /**
     * 单条连接内的推送循环；返回导致会话结束的异常（null 表示因 [shouldStop] 正常结束）。
     *
     * IDLE 的阻塞特性决定了实现方式：
     * - `IMAPFolder.idle()` 是**阻塞且不可中断**的（JavaMail 未提供可取消的 API），
     *   所以把它放到独立协程 / 独立 IO 线程上执行，本协程只做看门狗；
     * - 看门狗每秒检查一次 [shouldStop]，因此停止条件最迟约 1 秒生效
     *   （优于「最多 30 秒延迟」的退化方案）；
     * - 停止时通过 `folder.close(false)` 中止 IDLE：JavaMail 会在
     *   `waitIfIdle()` 里发送 DONE 并唤醒阻塞中的 `idle()`，这是官方 FAQ 推荐的做法；
     * - 服务器不支持 IDLE 能力时退化为 30 秒轮询邮件数量。
     */
    private suspend fun pushSession(
        folder: IMAPFolder,
        supportsIdle: Boolean,
        onNewMail: suspend (Long) -> Unit,
        shouldStop: () -> Boolean,
    ): Throwable? {
        val uidFolder: UIDFolder = folder

        if (!supportsIdle) {
            return pollLoop(folder, uidFolder, onNewMail, shouldStop)
        }

        return coroutineScope {
            // JavaMail 的事件回调运行在它自己的事件线程上，不能直接调用挂起函数，
            // 因此监听器只做非阻塞投递，由本作用域内的消费者协程串行回调 onNewMail
            val events = Channel<Long>(Channel.CONFLATED)
            val consumer = launch {
                for (uid in events) notifyNewMail(onNewMail, uid)
            }

            val listener = object : MessageCountAdapter() {
                override fun messagesAdded(event: MessageCountEvent) {
                    val newest = newestUid(event.messages, uidFolder, folder)
                    Logs.i(TAG, "收到新邮件通知：count=${event.messages?.size ?: 0} uid=$newest")
                    events.trySend(newest)
                }
            }
            folder.addMessageCountListener(listener)

            val failure = AtomicReference<Throwable?>(null)
            val idler = launch(Dispatchers.IO) {
                try {
                    // idle() 收到通知后会继续等待下一条，正常情况一次调用即可长期驻留
                    while (isActive && folder.isOpen) {
                        folder.idle()
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    failure.compareAndSet(null, t)
                }
            }

            try {
                while (!shouldStop() && idler.isActive) {
                    delay(STOP_CHECK_INTERVAL_MILLIS)
                }
            } finally {
                // 先中止 IDLE 再取消协程：直接 cancel 无法打断阻塞中的 socket 读取
                runCatching { folder.close(false) }
                idler.cancel()
                withTimeoutOrNull(IDLE_JOIN_TIMEOUT_MILLIS) { idler.join() }
                runCatching { folder.removeMessageCountListener(listener) }
                consumer.cancel()
            }

            failure.get()
        }
    }

    /**
     * 不支持 IDLE 时的退化方案：每 30 秒轮询一次邮件数量。
     *
     * `getMessageCount()` 会触发 NOOP，服务器在有新邮件时通过 EXISTS 更新数量。
     */
    private suspend fun pollLoop(
        folder: IMAPFolder,
        uidFolder: UIDFolder,
        onNewMail: suspend (Long) -> Unit,
        shouldStop: () -> Boolean,
    ): Throwable? {
        var lastCount = folder.messageCount
        Logs.i(TAG, "服务器不支持 IDLE，退化为 ${POLL_INTERVAL_MILLIS / 1000} 秒轮询：count=$lastCount")

        while (!shouldStop()) {
            waitSlices(POLL_INTERVAL_MILLIS, shouldStop)
            if (shouldStop()) break

            val count = folder.messageCount
            when {
                count > lastCount -> {
                    val uid = newestUid(null, uidFolder, folder)
                    lastCount = count
                    Logs.i(TAG, "轮询发现新邮件：count=$count uid=$uid")
                    notifyNewMail(onNewMail, uid)
                }

                count < lastCount -> {
                    // 其它客户端删除了邮件：只更新基准，不通知上层
                    Logs.d(TAG, "轮询发现邮件数减少：$lastCount -> $count")
                    lastCount = count
                }
            }
        }
        return null
    }

    /**
     * 取「最新 UID」。
     *
     * 优先使用事件携带的消息；事件里取不到时退化为最后一条消息的 UID；
     * 仍然取不到则返回 0，表示「服务器报告数量有变化但 UID 未知」，
     * 上层可据此直接触发一次普通增量同步（不会丢邮件）。
     */
    private fun newestUid(messages: Array<Message>?, uidFolder: UIDFolder, folder: IMAPFolder): Long {
        val fromEvent = messages
            ?.mapNotNull { message -> runCatching { uidFolder.getUID(message) }.getOrNull() }
            ?.filter { it > 0L }
            ?.maxOrNull()
        if (fromEvent != null) return fromEvent

        return runCatching {
            val count = folder.messageCount
            if (count <= 0) 0L else uidFolder.getUID(folder.getMessage(count))
        }.getOrDefault(0L)
    }

    /** 回调上层；上层异常不应打断推送会话（连接仍然健康，继续等待下一封） */
    private suspend fun notifyNewMail(onNewMail: suspend (Long) -> Unit, uid: Long) {
        try {
            onNewMail(uid)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.e(TAG, "新邮件回调处理失败：uid=$uid", t)
        }
    }

    /** 分片等待：每片 1 秒检查一次停止条件，避免长时间无法退出 */
    private suspend fun waitSlices(totalMillis: Long, shouldStop: () -> Boolean) {
        var remaining = totalMillis
        while (remaining > 0L && !shouldStop()) {
            val slice = minOf(remaining, STOP_CHECK_INTERVAL_MILLIS)
            delay(slice)
            remaining -= slice
        }
    }

    /** 查找属性含 `\Trash` 的文件夹（排除当前文件夹自身） */
    private fun findTrashFolder(store: Store, current: String): Folder? =
        try {
            store.defaultFolder.list("*").firstOrNull { listed ->
                val attributes = (listed as? IMAPFolder)?.attributes
                val isTrash = attributes?.any { it.equals("\\Trash", ignoreCase = true) } == true
                isTrash && !listed.fullName.equals(current, ignoreCase = true)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.w(TAG, "查找回收站文件夹失败", t)
            null
        }

    private companion object {
        const val TAG = "ImapClient"

        /** 不支持 IDLE 时的轮询间隔：需求规定 30 秒 */
        const val POLL_INTERVAL_MILLIS = 30_000L

        /** 停止条件检查间隔：IDLE 阻塞期间每秒检查一次，保证及时退出 */
        const val STOP_CHECK_INTERVAL_MILLIS = 1_000L

        /** 等待 IDLE 线程收尾的最长时间（close() 已中止 IDLE，这里只是兜底） */
        const val IDLE_JOIN_TIMEOUT_MILLIS = 3_000L

        /** 会话持续超过该时长视为「稳定」，退避计数归零 */
        const val STABLE_SESSION_MILLIS = 60_000L

        /** MIME 递归解析深度上限，防止恶意嵌套导致栈溢出 */
        const val MAX_MIME_DEPTH = 10

        /** 超时下限：避免调用方传 0 被 JavaMail 当作「永不超时」 */
        const val MIN_TIMEOUT_MILLIS = 1_000

        /** 正文截断提示（需求规定） */
        const val TRUNCATED_SUFFIX = "\n\n…（正文过长已截断）"

        /** IMAPFolder.UNKNOWN_SEPARATOR：分隔符未知时的哨兵值 */
        const val UNKNOWN_SEPARATOR = '\uffff'
    }
}
