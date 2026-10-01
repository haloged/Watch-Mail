package com.wm.wearmail.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.prefs.AppSettings
import com.wm.wearmail.mail.MailError
import com.wm.wearmail.mail.OutgoingMessage
import com.wm.wearmail.mail.RemoteMail
import com.wm.wearmail.mail.toMailError
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AccountSyncResult
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.Draft
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.model.MailAddress
import com.wm.wearmail.model.SyncReason
import com.wm.wearmail.model.SyncReport
import com.wm.wearmail.model.SyncUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 同步引擎（[SyncService] 的唯一实现）。
 *
 * 设计要点：
 * 1. **单飞（single-flight）**：所有同步动作共用一把 [syncMutex]。
 *    前台定时循环、WorkManager 后台任务、用户下拉刷新可能同时触发，
 *    并发跑 IMAP 会成倍消耗手表的网络与电量，因此统一串行化：
 *    后到的请求排队等待，而不是被丢弃（等待期间前一次同步的结果已经写库，
 *    增量同步基准会随之更新，所以排队后的同步通常很快）。
 * 2. **离线优先**：已读/星标等标记「先改本地，再同步远端」，远端失败不回滚；
 *    删除则相反——远端成功才删本地，避免本地删了服务器还留着导致下次同步「复活」。
 * 3. **首次同步只拉 50 封元数据**，且不推送通知，避免冷启动刷屏与卡顿。
 * 4. 日志只记录账户 id、UID、数量、耗时等非敏感信息，**绝不记录密码/授权码/正文**。
 */
class SyncEngine(private val container: AppContainer) : SyncService {

    private val _state = MutableStateFlow(SyncUiState())

    override val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** 同步互斥锁：保证任何时刻只有一个同步任务在运行 */
    private val syncMutex = Mutex()

    /** 前台定时同步循环的 Job（只取消这个 Job，不取消 container.scope 本身） */
    @Volatile
    private var foregroundJob: Job? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun startForegroundLoop(scope: CoroutineScope) {
        // 重复调用（例如 Activity 反复 onStart）时先停掉旧循环，避免多份定时器叠加
        stopForegroundLoop()
        foregroundJob = scope.launch {
            // 首次立即同步一次（启动同步），此路径不推送新邮件通知
            runSyncQuietly(SyncReason.STARTUP, "启动同步失败")
            while (isActive) {
                val minutes = container.settings.settings.value.foregroundSyncMinutes
                if (minutes <= 0) {
                    // -1 表示「仅手动刷新」：不自动同步，但仍定期重新读取设置
                    delay(MANUAL_RECHECK_MILLIS)
                    continue
                }
                delay(minutes * MINUTE_MILLIS)
                if (!isActive) break
                runSyncQuietly(SyncReason.FOREGROUND, "前台定时同步失败")
            }
        }
        Logs.i(TAG, "前台同步循环已启动")
    }

    override fun stopForegroundLoop() {
        val job = foregroundJob
        foregroundJob = null
        // 只取消定时循环自身；container.scope 由 AppContainer 持有，不能在此取消
        job?.cancel()
        if (job != null) {
            Logs.i(TAG, "前台同步循环已停止")
        }
    }

    override fun scheduleBackgroundSync() {
        try {
            val settings = container.settings.settings.value
            // 系统对周期任务的下限是 15 分钟，低于该值会被静默拉长，这里主动收敛
            val minutes = maxOf(MIN_BACKGROUND_MINUTES, settings.backgroundSyncMinutes).toLong()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(container.app).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Logs.i(TAG, "后台周期同步已注册：每 $minutes 分钟")
        } catch (t: Throwable) {
            // WorkManager 初始化失败（例如被厂商裁剪）不应影响前台使用
            Logs.e(TAG, "注册后台周期同步失败", t)
        }
    }

    override fun cancelBackgroundSync() {
        try {
            WorkManager.getInstance(container.app).cancelUniqueWork(WORK_NAME)
            Logs.i(TAG, "后台周期同步已取消")
        } catch (t: Throwable) {
            Logs.e(TAG, "取消后台周期同步失败", t)
        }
    }

    // ------------------------------------------------------------------
    // 同步动作
    // ------------------------------------------------------------------

    override suspend fun syncAll(reason: SyncReason): SyncReport = syncMutex.withLock {
        val startedAt = System.currentTimeMillis()
        _state.update { it.copy(running = true, reason = reason, lastError = null) }
        Logs.i(TAG, "开始同步：${reason.label}")

        var accounts = container.accounts.accounts.value
        if (accounts.isEmpty()) {
            // 冷启动时账户快照可能还没预热完成：主动读一次数据库，
            // 避免「启动同步」因空快照而空转（仍无账户时才按契约返回空报告）。
            accounts = runCatchingQuietly("读取账户列表失败") { container.accounts.load() }
                ?: container.accounts.accounts.value
        }
        val results = mutableListOf<AccountSyncResult>()

        if (accounts.isEmpty()) {
            // 尚未添加账户：不是错误，保持 lastError 为空，避免界面弹出无意义的红条
            Logs.i(TAG, "尚未添加账户，跳过同步")
        } else {
            for (account in accounts) {
                // 串行同步：手表内存与带宽都紧张，并发同步只会互相拖慢
                results += syncAccountLocked(account, reason)
            }
        }

        val finishedAt = System.currentTimeMillis()
        val report = if (results.isEmpty()) {
            SyncReport.empty(reason)
        } else {
            SyncReport(
                reason = reason,
                results = results.toList(),
                startedAt = startedAt,
                finishedAt = finishedAt,
            )
        }

        val failureMessage = results.firstOrNull { !it.success }?.errorMessage
        val anySuccess = results.any { it.success }
        _state.update { current ->
            current.copy(
                running = false,
                reason = reason,
                lastSuccessAt = if (anySuccess) finishedAt else current.lastSuccessAt,
                lastError = failureMessage,
                newMailCount = report.newMailCount,
            )
        }
        Logs.i(
            TAG,
            "同步结束：成功=${report.successCount} 失败=${report.failedCount} " +
                "新增=${report.newMailCount} 耗时=${report.durationMillis}ms",
        )

        // 缓存 LRU 淘汰放在同步末尾，避免与写入竞争
        runQuietly("缓存淘汰失败") {
            val settings = container.settings.settings.value
            container.emails.enforceLimits(settings.maxCachedHeaders, settings.maxCachedBodies)
        }

        // 网络可用时顺带投递待发草稿（离线写好的信不必等用户手动重试）
        if (isNetworkAvailable()) {
            val sent = flushPendingDraftsQuietly()
            if (sent > 0) {
                Logs.i(TAG, "已自动投递 $sent 封待发草稿")
            }
        }

        report
    }

    override suspend fun syncAccount(accountId: Long, reason: SyncReason): AccountSyncResult =
        syncMutex.withLock {
            val account = container.accounts.account(accountId)
            if (account == null) {
                Logs.w(TAG, "同步失败：账户 $accountId 不存在")
                return@withLock AccountSyncResult(
                    accountId = accountId,
                    success = false,
                    durationMillis = 0L,
                    errorMessage = "账户不存在，可能已被删除",
                )
            }
            _state.update { it.copy(running = true, reason = reason, lastError = null) }
            val result = syncAccountLocked(account, reason)
            _state.update { current ->
                current.copy(
                    running = false,
                    reason = reason,
                    lastSuccessAt = if (result.success) {
                        System.currentTimeMillis()
                    } else {
                        current.lastSuccessAt
                    },
                    lastError = result.errorMessage,
                    newMailCount = result.newMailCount,
                )
            }
            result
        }

    // ------------------------------------------------------------------
    // 邮件动作
    // ------------------------------------------------------------------

    override suspend fun loadBody(emailId: Long): Result<String> {
        // 1) 本地缓存优先：离线也能看已下载过的正文
        try {
            val cached = container.emails.cachedBody(emailId)
            if (cached != null) return Result.success(cached)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, "读取正文缓存失败：emailId=$emailId", t)
        }

        if (!isNetworkAvailable()) {
            return Result.failure(MailError.offline())
        }

        return try {
            val meta = container.emails.email(emailId)
                ?: return Result.failure(MailError.Unknown("邮件不存在或已被删除"))
            val account = container.accounts.account(meta.accountId)
                ?: return Result.failure(MailError.Config("账户不存在，请重新配置"))

            val body = withOAuthRetry(account) { secrets ->
                container.imap.fetchBody(account, secrets, meta.folder, meta.uid)
            }
            // 下载成功立即写入缓存，后续离线可读
            body.onSuccess { text -> container.emails.cacheBody(emailId, text) }
            if (body.isFailure) {
                val error = body.exceptionOrNull()?.toMailError("正文下载失败")
                    ?: MailError.Unknown("正文下载失败")
                Logs.w(TAG, "正文下载失败：emailId=$emailId uid=${meta.uid} ${error.userMessage}")
            }
            body
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("加载正文失败")
            Logs.w(TAG, "加载正文异常：emailId=$emailId", error)
            Result.failure(error)
        }
    }

    override suspend fun setRead(emailId: Long, read: Boolean): Result<Unit> {
        // 离线优先取舍：先改本地让界面立即反馈，远端失败不回滚（下次全量校正即可），
        // 但把失败结果如实返回给 UI，由用户决定是否重试。
        return try {
            container.emails.setRead(emailId, read)
            syncFlagToRemote(emailId, "已读标记") { account, secrets, meta ->
                container.imap.setSeen(account, secrets, meta.folder, meta.uid, read)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("标记已读失败")
            Logs.w(TAG, "标记已读异常：emailId=$emailId", error)
            Result.failure(error)
        }
    }

    override suspend fun setFlagged(emailId: Long, flagged: Boolean): Result<Unit> {
        // 同 setRead：本地先行，远端失败不回滚
        return try {
            container.emails.setFlagged(emailId, flagged)
            syncFlagToRemote(emailId, "星标") { account, secrets, meta ->
                container.imap.setFlagged(account, secrets, meta.folder, meta.uid, flagged)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("标记星标失败")
            Logs.w(TAG, "标记星标异常：emailId=$emailId", error)
            Result.failure(error)
        }
    }

    override suspend fun deleteEmail(emailId: Long): Result<Unit> {
        if (!isNetworkAvailable()) {
            return Result.failure(MailError.offline())
        }
        return try {
            val meta = container.emails.email(emailId)
                ?: return Result.failure(MailError.Unknown("邮件不存在或已被删除"))
            val account = container.accounts.account(meta.accountId)
                ?: return Result.failure(MailError.Config("账户不存在，请重新配置"))

            // 必须远端成功后再删本地：否则服务器上的邮件会在下次同步时「复活」
            withOAuthRetry(account) { secrets ->
                container.imap.deleteMessage(account, secrets, meta.folder, meta.uid)
            }.fold(
                onSuccess = {
                    container.emails.delete(emailId)
                    Logs.i(TAG, "邮件已删除：emailId=$emailId")
                    Result.success(Unit)
                },
                onFailure = { throwable ->
                    val error = throwable.toMailError("删除邮件失败")
                    Logs.w(TAG, "远端删除失败，保留本地记录：emailId=$emailId", error)
                    Result.failure(error)
                },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("删除邮件失败")
            Logs.w(TAG, "删除邮件异常：emailId=$emailId", error)
            Result.failure(error)
        }
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    override suspend fun send(
        accountId: Long,
        to: String,
        subject: String,
        body: String,
    ): Result<Unit> {
        val recipient = to.trim()
        if (recipient.isEmpty()) {
            return Result.failure(MailError.Config("收件人不能为空"))
        }

        // 需求为「正文建议 500 字以内」：这里选择截断而不是拒绝发送（截断优于丢内容），
        // 草稿与真正发出的内容保持一致，避免重试时正文变长。
        val safeBody = if (body.length > OutgoingMessage.MAX_BODY_CHARS) {
            Logs.w(TAG, "正文超长已截断：原长=${body.length} 上限=${OutgoingMessage.MAX_BODY_CHARS}")
            body.take(OutgoingMessage.MAX_BODY_CHARS)
        } else {
            body
        }

        return try {
            val account = container.accounts.account(accountId)
                ?: return Result.failure(MailError.Config("账户不存在，请重新配置"))

            if (!isNetworkAvailable()) {
                // 明确无网络时直接落草稿，不必白等 10 秒连接超时
                return saveDraftAndFail(
                    accountId = accountId,
                    to = recipient,
                    subject = subject,
                    body = safeBody,
                    error = MailError.Network("网络不可用，已存为草稿"),
                )
            }

            val message = OutgoingMessage(to = listOf(recipient), subject = subject, body = safeBody)
            val result = try {
                withOAuthRetry(account) { secrets -> container.smtp.send(account, secrets, message) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Result.failure(t.toMailError("发送失败"))
            }

            result.fold(
                onSuccess = {
                    runQuietly("记录常用联系人失败") {
                        container.contacts.record(null, recipient)
                    }
                    Logs.i(TAG, "邮件已发送：账户=$accountId")
                    Result.success(Unit)
                },
                onFailure = { throwable ->
                    val error = throwable.toMailError("发送失败")
                    Logs.w(TAG, "发送失败，转为草稿：账户=$accountId", error)
                    saveDraftAndFail(
                        accountId = accountId,
                        to = recipient,
                        subject = subject,
                        body = safeBody,
                        error = draftSavedError(error),
                    )
                },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("发送失败")
            Logs.w(TAG, "发送异常：账户=$accountId", error)
            // 任何意外都要保证内容不丢
            saveDraftAndFail(accountId, recipient, subject, safeBody, draftSavedError(error))
        }
    }

    override suspend fun sendDraft(draftId: Long): Result<Unit> {
        return try {
            val draft = container.drafts.draft(draftId)
                ?: return Result.failure(MailError.Config("草稿不存在或已被删除"))

            val recipients = parseRecipients(draft.to)
            if (recipients.isEmpty()) {
                return failDraft(draftId, MailError.Config("草稿收件人为空，无法发送"))
            }

            val account = container.accounts.account(draft.accountId)
                ?: return failDraft(draftId, MailError.Config("账户不存在，请重新配置"))
            if (!isNetworkAvailable()) {
                return failDraft(draftId, MailError.offline())
            }

            val result = try {
                withOAuthRetry(account) { secrets ->
                    container.smtp.send(
                        account,
                        secrets,
                        OutgoingMessage(
                            to = recipients,
                            subject = draft.subject,
                            body = draft.body,
                        ),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Result.failure(t.toMailError("发送失败"))
            }

            result.fold(
                onSuccess = {
                    container.drafts.delete(draftId)
                    runQuietly("记录常用联系人失败") {
                        container.contacts.record(null, recipients.first())
                    }
                    Logs.i(TAG, "草稿已投递：draftId=$draftId")
                    Result.success(Unit)
                },
                onFailure = { throwable ->
                    val error = throwable.toMailError("草稿发送失败")
                    Logs.w(TAG, "草稿投递失败：draftId=$draftId", error)
                    failDraft(draftId, error)
                },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("投递草稿失败")
            Logs.w(TAG, "投递草稿异常：draftId=$draftId", error)
            failDraft(draftId, error)
        }
    }

    override suspend fun flushPendingDrafts(): Int {
        if (!isNetworkAvailable()) {
            Logs.d(TAG, "当前无网络，跳过待发草稿投递")
            return 0
        }
        val pending = try {
            container.drafts.pending()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, "读取待发草稿失败", t)
            return 0
        }

        var sent = 0
        for (draft in pending) {
            // 单封草稿失败不能中断整批投递
            val result = try {
                sendDraft(draft.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Logs.w(TAG, "投递草稿异常：draftId=${draft.id}", t)
                Result.failure(t.toMailError("投递草稿失败"))
            }
            if (result.isSuccess) sent++
        }
        return sent
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 真正的单账户同步：调用方必须已持有 [syncMutex] */
    private suspend fun syncAccountLocked(account: Account, reason: SyncReason): AccountSyncResult {
        val startedAt = System.currentTimeMillis()

        val settings = container.settings.settings.value
        if (isNetworkBlocked(settings)) {
            val message = MailError.offline().userMessage
            Logs.w(TAG, "账户 ${account.id} 跳过同步：$message")
            return AccountSyncResult(
                accountId = account.id,
                success = false,
                durationMillis = System.currentTimeMillis() - startedAt,
                errorMessage = message,
            )
        }

        return try {
            val secrets = container.oauthTokens.freshSecrets(account).getOrElse { error ->
                Logs.w(TAG, "账户 ${account.id} 凭据不可用：${error.toMailError().javaClass.simpleName}")
                return AccountSyncResult(
                    accountId = account.id,
                    success = false,
                    durationMillis = System.currentTimeMillis() - startedAt,
                    errorMessage = error.toMailError().userMessage,
                )
            }
            val first = syncInbox(account, secrets, reason, startedAt)
            // OAuth2：令牌可能已被撤销或本地时钟偏差导致"看起来未过期"，
            // 失败时强制刷新令牌再重试一次（刷新失败则返回首次结果）
            if (account.authType != AuthType.OAUTH2 || first.success) return first
            Logs.i(TAG, "账户 ${account.id} 同步失败，强制刷新 OAuth2 令牌后重试一次")
            val refreshed = container.oauthTokens.forceRefresh(account).getOrElse { return first }
            syncInbox(account, refreshed, reason, startedAt)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val error = t.toMailError("同步失败")
            Logs.w(TAG, "账户 ${account.id} 同步异常：${error.userMessage}", error)
            AccountSyncResult(
                accountId = account.id,
                success = false,
                durationMillis = System.currentTimeMillis() - startedAt,
                errorMessage = error.userMessage,
            )
        }
    }

    /** 拉取收件箱并落库；返回该账户的同步结果 */
    private suspend fun syncInbox(
        account: Account,
        secrets: AccountSecrets,
        reason: SyncReason,
        startedAt: Long,
    ): AccountSyncResult {
        val lastUid = container.emails.latestUid(account.id, Account.FOLDER_INBOX)
        val fetched = if (lastUid <= 0L) {
            // 首次同步：只拉最新 50 封元数据（绝不下载正文）
            Logs.d(TAG, "账户 ${account.id} 首次同步，拉取最近 $FIRST_SYNC_LIMIT 封")
            container.imap.fetchRecent(account, secrets, Account.FOLDER_INBOX, FIRST_SYNC_LIMIT)
        } else {
            // 增量同步：以「上次成功时间 - 24 小时」为下限，容忍服务器时间与本地时钟偏差；
            // lastSyncAt 为 0（异常状态）时不加 SINCE 条件，交给 UID 策略兜底。
            val since = if (account.lastSyncAt > 0L) account.lastSyncAt - DAY_MILLIS else null
            Logs.d(TAG, "账户 ${account.id} 增量同步：基准 UID=$lastUid")
            container.imap.fetchNewerThan(
                account = account,
                secrets = secrets,
                folder = Account.FOLDER_INBOX,
                lastUid = lastUid,
                sinceMillis = since,
            )
        }

        val remotes = fetched.getOrNull()
        if (remotes == null) {
            val error = fetched.exceptionOrNull()?.toMailError("同步失败")
                ?: MailError.Unknown("同步失败")
            Logs.w(TAG, "账户 ${account.id} 拉取失败：${error.userMessage}", error)
            return AccountSyncResult(
                accountId = account.id,
                success = false,
                durationMillis = System.currentTimeMillis() - startedAt,
                errorMessage = error.userMessage,
            )
        }

        val metas = remotes.map { it.toEmailMeta(account.id) }
        val newCount = container.emails.upsertAll(metas)

        // 发件人自动累积为常用联系人：只取最新 10 封，避免一次性写入上百行
        runQuietly("记录常用联系人失败") {
            remotes.asSequence()
                .sortedByDescending { it.dateMillis }
                .take(CONTACT_RECORD_LIMIT)
                .forEach { mail ->
                    val address = mail.from.address.trim()
                    val unknown = MailAddress.UNKNOWN.address
                    if (address.isNotEmpty() && !address.equals(unknown, ignoreCase = true)) {
                        container.contacts.record(mail.from.name, address)
                    }
                }
        }

        runQuietly("更新最近同步时间失败") { container.accounts.touchLastSync(account.id) }

        // 新邮件通知：启动同步不提醒（首次可能一次性入库 50 封）；每轮最多 3 条
        if (reason != SyncReason.STARTUP && newCount > 0) {
            notifyNewMails(account, metas)
        }

        Logs.i(TAG, "账户 ${account.id} 同步完成：新入库 $newCount 封")
        return AccountSyncResult(
            accountId = account.id,
            success = true,
            newMailCount = newCount,
            durationMillis = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * 推送新邮件通知。
     *
     * [fetched] 是协议层产出的元数据（还没有本地主键），通知点击需要 emailId，
     * 因此这里回查一次仓储，只对「本轮新入库的 UID」取最新若干封发通知。
     */
    private suspend fun notifyNewMails(account: Account, fetched: List<EmailMeta>) {
        try {
            val newUids = fetched.map { it.uid }.toSet()
            // 仓储实现异常时不阻塞同步：加超时兜底
            val recent = withTimeoutOrNull(NOTIFY_LOOKUP_TIMEOUT_MILLIS) {
                container.emails.observeInbox(account.id, NOTIFY_INBOX_QUERY_LIMIT).first()
            } ?: return

            recent.asSequence()
                .filter { it.uid in newUids }
                .sortedByDescending { it.dateMillis }
                .take(MAX_NOTIFICATIONS_PER_SYNC)
                .forEach { meta -> container.notifications.notifyNewMail(account, meta) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, "推送新邮件通知失败（不影响同步）", t)
        }
    }

    /** 已读/星标标记的远端同步：本地已改，远端失败只返回失败不回滚 */
    private suspend fun syncFlagToRemote(
        emailId: Long,
        action: String,
        remote: suspend (Account, AccountSecrets, EmailMeta) -> Result<Unit>,
    ): Result<Unit> {
        if (!isNetworkAvailable()) {
            return Result.failure(MailError.offline())
        }
        val meta = container.emails.email(emailId)
            ?: return Result.failure(MailError.Unknown("邮件不存在或已被删除"))
        val account = container.accounts.account(meta.accountId)
            ?: return Result.failure(MailError.Config("账户不存在，请重新配置"))

        val result = try {
            withOAuthRetry(account) { secrets -> remote(account, secrets, meta) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Result.failure(t.toMailError("$action 同步失败"))
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull()?.toMailError("$action 同步失败")
                ?: MailError.Unknown("$action 同步失败")
            // 本地已更新且不回滚：界面保持用户看到的状态，只提示远端未同步
            Logs.w(TAG, "$action 远端同步失败（本地已更新，不回滚）：emailId=$emailId", error)
            return Result.failure(error)
        }
        return Result.success(Unit)
    }

    /** 发送失败时保存草稿，并把「已存为草稿」的信息带回给调用方 */
    private suspend fun saveDraftAndFail(
        accountId: Long,
        to: String,
        subject: String,
        body: String,
        error: MailError,
    ): Result<Unit> {
        val draftId = runCatchingQuietly("保存草稿失败") {
            container.drafts.save(
                Draft(
                    accountId = accountId,
                    to = to,
                    subject = subject,
                    body = body,
                    createdAt = System.currentTimeMillis(),
                    lastError = error.userMessage,
                ),
            )
        }
        Logs.w(TAG, "发送失败：${error.userMessage}（草稿 id=${draftId ?: -1L}）")
        return Result.failure(error)
    }

    /** 草稿投递失败：记录失败原因后返回失败 */
    private suspend fun failDraft(draftId: Long, error: MailError): Result<Unit> {
        runQuietly("记录草稿失败原因失败") {
            container.drafts.markError(draftId, error.userMessage)
        }
        return Result.failure(error)
    }

    /** 给错误消息补上「已存为草稿」，让 UI 能明确告知用户内容没丢 */
    private fun draftSavedError(error: MailError): MailError = when (error) {
        is MailError.Network -> MailError.Network("网络不可用，已存为草稿", error)
        is MailError.Timeout -> MailError.Timeout("连接超时，已存为草稿", error)
        is MailError.Auth -> MailError.Auth("账号或密码/授权码错误，已存为草稿", error)
        is MailError.Protocol -> MailError.Protocol("服务器响应异常，已存为草稿", error)
        is MailError.Config -> MailError.Config("服务器地址或端口配置有误，已存为草稿", error)
        is MailError.Unknown -> MailError.Unknown("发送失败，已存为草稿", error)
    }

    /** 收件人字符串（草稿可能保存多个，用逗号/分号分隔）解析为地址列表 */
    private fun parseRecipients(raw: String): List<String> =
        raw.split(',', ';', '，', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(OutgoingMessage.MAX_RECIPIENTS)

    /** 协议层的 [RemoteMail] 补齐账户与文件夹信息后转为本地模型 */
    private fun RemoteMail.toEmailMeta(accountId: Long): EmailMeta = EmailMeta(
        accountId = accountId,
        folder = Account.FOLDER_INBOX,
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

    // ------------------------------------------------------------------
    // 网络判断
    // ------------------------------------------------------------------

    /** 是否存在可用网络（有 INTERNET 能力的活动网络） */
    /**
     * 取账户凭据，并按需**自动刷新 OAuth2 令牌**。
     *
     * 所有网络调用（收信、正文、标记、删除、发信）统一走这里：
     * Microsoft 的 access token 只有约 1 小时有效期，若不集中刷新，
     * 每个调用点都要各写一遍刷新逻辑，必然有入口被漏掉。
     */
    private suspend fun credentialsFor(accountId: Long): Result<Pair<Account, AccountSecrets>> {
        val account = container.accounts.account(accountId)
            ?: return Result.failure(MailError.Config("账户不存在，请重新配置"))
        val secrets = container.oauthTokens.freshSecrets(account).getOrElse { error ->
            Logs.w(TAG, "账户 $accountId 凭据不可用：${error.toMailError().javaClass.simpleName}")
            return Result.failure(error.toMailError())
        }
        if (secrets.isEmpty) return Result.failure(MailError.Auth(CREDENTIALS_MISSING))
        return Result.success(account to secrets)
    }

    /**
     * 执行一次需要凭据的网络操作；OAuth2 账户**认证失败时强制刷新令牌再重试一次**。
     *
     * 为什么还要强制刷新（而不是只靠过期判断）：令牌可能已被用户在别处撤销，
     * 或手表时钟偏差导致本地认为"还没过期"。只重试一次，避免真的密码错误时反复打服务器。
     */
    private suspend fun <T> withOAuthRetry(
        account: Account,
        block: suspend (AccountSecrets) -> Result<T>,
    ): Result<T> {
        val secrets = container.oauthTokens.freshSecrets(account).getOrElse { return Result.failure(it) }
        val first = block(secrets)
        if (account.authType != AuthType.OAUTH2) return first

        val error = first.exceptionOrNull()?.toMailError()
        if (error !is MailError.Auth) return first

        Logs.i(TAG, "账户 ${account.id} OAuth2 认证失败，强制刷新令牌后重试一次")
        val refreshed = container.oauthTokens.forceRefresh(account).getOrElse { return first }
        return block(refreshed)
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val manager = container.app
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager == null) {
                // 拿不到系统服务时不做拦截，让协议层的错误提示兜底
                true
            } else {
                val network = manager.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "网络状态查询失败，按可用处理", t)
            true
        }
    }

    /** 当前活动网络是否为 Wi-Fi */
    private fun isWifiConnected(): Boolean {
        return try {
            val manager = container.app
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager == null) {
                false
            } else {
                val network = manager.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "Wi-Fi 状态查询失败，按非 Wi-Fi 处理", t)
            false
        }
    }

    /** 当前是否应当跳过同步（完全无网络，或开启了「仅 Wi-Fi」但不在 Wi-Fi 上） */
    private fun isNetworkBlocked(settings: AppSettings): Boolean =
        !isNetworkAvailable() || (settings.wifiOnlySync && !isWifiConnected())

    // ------------------------------------------------------------------
    // 协程工具
    // ------------------------------------------------------------------

    /** 触发一次同步并吞掉业务异常；协程取消必须继续向上传播 */
    private suspend fun runSyncQuietly(reason: SyncReason, failureTag: String) {
        try {
            syncAll(reason)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, failureTag, t)
        }
    }

    /** 执行一段可能失败的逻辑（如写库、发通知），失败只记日志不影响主流程 */
    private suspend fun runQuietly(message: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, message, t)
        }
    }

    /** 同上，但需要拿到返回值（失败返回 null） */
    private suspend fun <T> runCatchingQuietly(message: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, message, t)
            null
        }
    }

    /** 投递全部待发草稿并吞掉异常，返回成功数量 */
    private suspend fun flushPendingDraftsQuietly(): Int {
        return try {
            flushPendingDrafts()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Logs.w(TAG, "自动投递草稿失败", t)
            0
        }
    }

    companion object {
        private const val TAG = "SyncEngine"

        /** 后台周期同步的唯一任务名（重复注册时按此覆盖，不会出现多份任务） */
        const val WORK_NAME: String = "wearmail_background_sync"

        /** WorkManager 周期下限（分钟） */
        private const val MIN_BACKGROUND_MINUTES = 15

        /** 首次同步拉取封数（需求：50 封元数据） */
        private const val FIRST_SYNC_LIMIT = 50

        /** 每轮同步最多记录的联系人数量，避免大量写库 */
        private const val CONTACT_RECORD_LIMIT = 10

        /** 每轮同步最多推送的通知条数 */
        private const val MAX_NOTIFICATIONS_PER_SYNC = 3

        /** 通知回查仓储的超时，避免仓储 Flow 不发射时卡住同步 */
        private const val NOTIFY_LOOKUP_TIMEOUT_MILLIS = 3_000L

        /** 通知回查时的收件箱查询条数 */
        private const val NOTIFY_INBOX_QUERY_LIMIT = 20

        /** 增量同步的时间下界回退量：24 小时 */
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

        private const val MINUTE_MILLIS = 60_000L

        /** 「仅手动刷新」档位下重新读取设置的间隔 */
        private const val MANUAL_RECHECK_MILLIS = 60_000L

        private const val CREDENTIALS_MISSING = "凭据缺失或解密失败，请重新配置账户"
    }
}
