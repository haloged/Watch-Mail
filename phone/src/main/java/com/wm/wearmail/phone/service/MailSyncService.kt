package com.wm.wearmail.phone.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.ImapClient
import com.wm.wearmail.mail.ImapClientImpl
import com.wm.wearmail.mail.oauth.MicrosoftOAuth
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.phone.data.PhoneStore
import com.wm.wearmail.phone.data.SyncedAccount
import com.wm.wearmail.phone.notify.MailNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机端收信服务（前台服务，常驻）。
 *
 * 工作方式：按用户设置的间隔（默认 1 分钟，见 `PhoneStore.syncIntervalMinutes`）
 * 对每个同步来的账户：
 * 1. **首次**（本地还没有 UID 基线）：只拉最新一封建立基线 —— **绝不**把存量邮件
 *    刷成一屏历史通知；
 * 2. 之后用 `fetchNewerThan(lastUid)` 增量拉取，新邮件逐封发**手机通知**
 *    （手表会同步手机通知 —— 这是绕开手表端通知限制的路径），并推进 UID；
 * 3. **OAuth2 账户**：access token 临近过期时用 refresh token + 客户端 ID 自行刷新
 *    （与手表端同一套 `MicrosoftOAuth` 实现），刷新后的令牌加密回写。
 *
 * 失败（网络抖动/服务器限流/令牌失效）只记日志并在下一轮重试，不影响其他账户。
 */
class MailSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: PhoneStore
    private lateinit var notifier: MailNotifier
    private val imap: ImapClient = ImapClientImpl()

    /** OAuth2 令牌刷新（与手表端共用实现） */
    private val oauth = MicrosoftOAuth()

    override fun onCreate() {
        super.onCreate()
        store = PhoneStore(this)
        notifier = MailNotifier(this)
        notifier.ensureChannels()
        startForeground(FOREGROUND_ID, notifier.buildServiceNotification())
        scope.launch { syncLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun syncLoop() {
        while (scope.isActive) {
            runCatching { syncOnce() }
                .onFailure { Logs.w(TAG, "同步循环异常：${it.javaClass.simpleName}") }
            // 每轮重新读取间隔：用户在设置里改完后，下一轮自动生效
            delay(store.syncIntervalMinutes.coerceIn(1, 60) * 60_000L)
        }
    }

    private suspend fun syncOnce() {
        val accounts = store.loadAccounts()
        if (accounts.isEmpty()) return
        val clientId = store.oauthClientId()
        for (synced in accounts) {
            val lastUid = store.lastSeenUid(synced.email)
            runCatching {
                val secrets = resolveSecrets(synced, clientId)
                if (secrets == null) {
                    Logs.w(TAG, "账户 ${synced.email} 凭据不可用，跳过本轮")
                    return@runCatching
                }
                val account = synced.toAccount()
                if (lastUid <= 0L) {
                    // 基线：只记录「当前最新 UID」，不通知任何存量邮件
                    imap.fetchRecent(account, secrets, limit = 1).getOrNull()
                        ?.maxOfOrNull { it.uid }
                        ?.let { store.setLastSeenUid(synced.email, it) }
                } else {
                    val newMails = imap.fetchNewerThan(
                        account = account,
                        secrets = secrets,
                        lastUid = lastUid,
                        limit = MAX_PER_SYNC,
                    ).getOrNull().orEmpty()
                    for (mail in newMails.sortedBy { it.uid }) {
                        if (store.notificationsEnabled) {
                            notifier.notifyNewMail(
                                from = mail.from.display,
                                subject = mail.subject,
                                accountEmail = synced.email,
                                uid = mail.uid,
                            )
                        }
                        store.setLastSeenUid(synced.email, mail.uid)
                    }
                }
            }.onFailure { Logs.w(TAG, "账户收信失败：${it.javaClass.simpleName}") }
        }
    }

    /**
     * 解析账户凭据。
     *
     * OAuth2 账户：access token 临近过期时用 refresh token + 客户端 ID 刷新
     * （刷新失败说明 refresh token 失效，只能回手表重新授权同步）。
     *
     * @return null 表示无法获得可用凭据（跳过本轮，不影响其他账户）
     */
    private suspend fun resolveSecrets(synced: SyncedAccount, clientId: String): AccountSecrets? {
        val tokens = synced.oauth
        if (tokens == null) {
            return if (synced.password.isNotBlank()) {
                AccountSecrets(password = synced.password)
            } else {
                null
            }
        }
        var current = tokens
        if (current.needsRefresh()) {
            val refreshToken = current.refreshToken
            if (refreshToken.isNullOrBlank() || clientId.isBlank()) {
                Logs.w(TAG, "OAuth2 令牌需刷新，但缺少 refresh token 或客户端 ID（请在手表重新同步）")
                return null
            }
            val refreshed = oauth.refresh(refreshToken, clientId).getOrNull()
            if (refreshed == null) {
                Logs.w(TAG, "OAuth2 令牌刷新失败（refresh token 可能已失效）")
                return null
            }
            current = refreshed
            // 回写新令牌（加密重存），避免每轮都重复刷新
            store.updateOauth(synced.email, refreshed)
        }
        return AccountSecrets(oauth = current)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { imap.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MailSyncService"

        /** 常驻通知 id（与邮件通知的 hash id 区分） */
        private const val FOREGROUND_ID = 1001

        /** 单轮每账户最多处理的新邮件数（防止长时间离线后一口气发太多通知） */
        private const val MAX_PER_SYNC = 10

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MailSyncService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MailSyncService::class.java)) }
        }
    }
}
