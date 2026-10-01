package com.wm.wearmail.core

import android.app.Application
import android.content.Context
import com.wm.wearmail.BuildConfig
import com.wm.wearmail.data.crypto.AndroidKeystoreKeyProvider
import com.wm.wearmail.data.crypto.CryptoManager
import com.wm.wearmail.data.crypto.CryptoManagerImpl
import com.wm.wearmail.data.db.MailDatabase
import com.wm.wearmail.data.prefs.SettingsStore
import com.wm.wearmail.data.prefs.SettingsStoreImpl
import com.wm.wearmail.data.repo.AccountRepository
import com.wm.wearmail.data.repo.AccountRepositoryImpl
import com.wm.wearmail.data.repo.ContactRepository
import com.wm.wearmail.data.repo.ContactRepositoryImpl
import com.wm.wearmail.data.repo.DraftRepository
import com.wm.wearmail.data.repo.DraftRepositoryImpl
import com.wm.wearmail.data.repo.EmailRepository
import com.wm.wearmail.data.repo.EmailRepositoryImpl
import com.wm.wearmail.mail.ImapClient
import com.wm.wearmail.mail.ImapClientImpl
import com.wm.wearmail.mail.ServerProbe
import com.wm.wearmail.mail.ServerProbeImpl
import com.wm.wearmail.mail.SmtpClient
import com.wm.wearmail.mail.SmtpClientImpl
import com.wm.wearmail.mail.oauth.MicrosoftOAuth
import com.wm.wearmail.mail.oauth.OAuthTokenService
import com.wm.wearmail.notify.MailNotifier
import com.wm.wearmail.notify.NotificationCenter
import com.wm.wearmail.pairing.PairingController
import com.wm.wearmail.pairing.PairingService
import com.wm.wearmail.sync.SyncEngine
import com.wm.wearmail.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 依赖容器（手写依赖注入）。
 *
 * 为什么不用 Hilt/Dagger：
 * - 手表应用模块少、依赖图浅（14 个单例），引入注解处理器会显著拖慢构建；
 * - 手写容器让「谁能拿到什么」一目了然，便于安全审查（例如凭据只经过
 *   [CryptoManager] 与 [AccountRepository]）。
 *
 * 全部依赖使用 `by lazy`：首次访问时才构造，避免冷启动时把
 * IMAP/数据库/通知渠道等全部初始化（冷启动目标 < 2 秒）。
 */
class AppContainer(val app: Application) {

    /** 应用级协程作用域：仅用于「不应随界面销毁而取消」的任务 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 敏感信息加解密（AES-256-GCM + Android Keystore） */
    val crypto: CryptoManager by lazy { CryptoManagerImpl(AndroidKeystoreKeyProvider(app)) }

    /** 全局设置 */
    val settings: SettingsStore by lazy { SettingsStoreImpl(app) }

    /** SQLite 数据库（4 张表：accounts / emails / email_bodies / drafts） */
    val db: MailDatabase by lazy { MailDatabase(app) }

    // ---------------- 仓储 ----------------
    val accounts: AccountRepository by lazy { AccountRepositoryImpl(db, crypto) }
    val emails: EmailRepository by lazy { EmailRepositoryImpl(db) }
    val drafts: DraftRepository by lazy { DraftRepositoryImpl(db) }
    val contacts: ContactRepository by lazy { ContactRepositoryImpl(db) }

    // ---------------- 邮件协议 ----------------
    val imap: ImapClient by lazy { ImapClientImpl() }
    val smtp: SmtpClient by lazy { SmtpClientImpl() }
    val probe: ServerProbe by lazy { ServerProbeImpl(imap, smtp) }

    // ---------------- OAuth2（Outlook / Office 365）----------------
    /** Microsoft OAuth2 客户端：设备码授权 + 令牌刷新（协议层，不碰数据库） */
    val oauth: MicrosoftOAuth by lazy { MicrosoftOAuth() }

    /**
     * OAuth2 令牌服务：同步前自动刷新 access token。
     *
     * 客户端 ID 的取值优先级：设置页填写的 > 构建期注入的
     * `gradle.properties: wearmail.microsoftOAuthClientId`。
     */
    val oauthTokens: OAuthTokenService by lazy {
        OAuthTokenService(
            accounts = accounts,
            settings = settings,
            oauth = oauth,
            buildConfigClientId = BuildConfig.MICROSOFT_OAUTH_CLIENT_ID,
        )
    }

    // ---------------- 通知 / 同步 / 配对 ----------------
    val notifications: MailNotifier by lazy { NotificationCenter(this) }
    val sync: SyncService by lazy { SyncEngine(this) }
    val pairing: PairingService by lazy { PairingController(this) }

    companion object {
        @Volatile
        private var fallbackInstance: AppContainer? = null

        /**
         * 获取应用级容器。
         *
         * 正常路径返回 [WearMailApp] 中的单例；若被非 Application 上下文调用
         * （例如某些测试场景），退化为进程内单例，保证数据库不被重复打开。
         */
        fun from(context: Context): AppContainer {
            val appContext = context.applicationContext ?: context
            if (appContext is WearMailApp) {
                return appContext.container
            }
            return fallbackInstance ?: synchronized(this) {
                fallbackInstance ?: AppContainer(appContext as Application).also {
                    fallbackInstance = it
                }
            }
        }
    }
}
