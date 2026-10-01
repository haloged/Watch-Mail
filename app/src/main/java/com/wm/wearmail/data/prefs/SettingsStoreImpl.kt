package com.wm.wearmail.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.wm.wearmail.core.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 设置读写实现（SharedPreferences + 内存 [StateFlow]）。
 *
 * 为什么不用 DataStore：DataStore 读取是 `suspend` 的，Compose 首帧需要设置值
 * 时会出现「先空后填」的闪烁；本实现构造时同步读一次（设置项不足 10 个，
 * 实际耗时远小于一帧），之后走内存 StateFlow，UI 首帧即可拿到正确值。
 */
class SettingsStoreImpl(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 内存快照：构造时同步读取落盘值，保证首帧可用 */
    private val state = MutableStateFlow(readFromPrefs())

    /** 保护「读-改-写」的互斥锁，避免并发 update 相互覆盖 */
    private val mutex = Mutex()

    /**
     * 账户级通知开关的**反向**集合：只记录被显式关闭的账户 id。
     *
     * 这样新增账户默认处于开启状态（符合「默认通知用户」的产品预期），
     * 也无需在添加账户时写一条设置。
     */
    @Volatile
    private var disabledNotificationAccounts: Set<String> =
        prefs.getStringSet(KEY_NOTIF_DISABLED_ACCOUNTS, emptySet())?.toSet() ?: emptySet()

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        mutex.withLock {
            val updated = transform(state.value)
            persist(updated)
            state.value = updated
            Logs.d(TAG, "设置已更新")
            updated
        }

    override fun accountNotifications(accountId: Long): Boolean =
        !disabledNotificationAccounts.contains(accountId.toString())

    override suspend fun setAccountNotifications(accountId: Long, enabled: Boolean) =
        mutex.withLock {
            val next = disabledNotificationAccounts.toMutableSet()
            if (enabled) next.remove(accountId.toString()) else next.add(accountId.toString())
            disabledNotificationAccounts = next
            // StringSet 必须写入新集合实例；直接改 prefs 返回的集合在部分 ROM 上不会生效
            prefs.edit().putStringSet(KEY_NOTIF_DISABLED_ACCOUNTS, next).apply()
            Logs.d(TAG, "账户 $accountId 通知开关已更新")
        }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 同步读取全部设置项（构造期调用一次） */
    private fun readFromPrefs(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            foregroundSyncMinutes = prefs.getInt(KEY_FOREGROUND_MINUTES, defaults.foregroundSyncMinutes),
            backgroundSyncMinutes = prefs.getInt(KEY_BACKGROUND_MINUTES, defaults.backgroundSyncMinutes),
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, defaults.notificationsEnabled),
            wifiOnlySync = prefs.getBoolean(KEY_WIFI_ONLY, defaults.wifiOnlySync),
            maxCachedHeaders = prefs.getInt(KEY_MAX_HEADERS, defaults.maxCachedHeaders),
            maxCachedBodies = prefs.getInt(KEY_MAX_BODIES, defaults.maxCachedBodies),
            signature = prefs.getString(KEY_SIGNATURE, defaults.signature).orEmpty(),
            microsoftOAuthClientId = prefs.getString(KEY_MS_CLIENT_ID, defaults.microsoftOAuthClientId).orEmpty(),
            microsoftOAuthTenant = prefs.getString(KEY_MS_TENANT, defaults.microsoftOAuthTenant).orEmpty(),
        )
    }

    /** 全量写回：设置项少，一次性 apply 比逐字段判断更简单可靠 */
    private fun persist(settings: AppSettings) {
        prefs.edit()
            .putInt(KEY_FOREGROUND_MINUTES, settings.foregroundSyncMinutes)
            .putInt(KEY_BACKGROUND_MINUTES, settings.backgroundSyncMinutes)
            .putBoolean(KEY_NOTIFICATIONS, settings.notificationsEnabled)
            .putBoolean(KEY_WIFI_ONLY, settings.wifiOnlySync)
            .putInt(KEY_MAX_HEADERS, settings.maxCachedHeaders)
            .putInt(KEY_MAX_BODIES, settings.maxCachedBodies)
            .putString(KEY_SIGNATURE, settings.signature)
            .putString(KEY_MS_CLIENT_ID, settings.microsoftOAuthClientId)
            .putString(KEY_MS_TENANT, settings.microsoftOAuthTenant)
            .apply()
    }

    companion object {
        private const val TAG = "SettingsStore"

        /** SharedPreferences 文件名（应用私有） */
        private const val PREFS_NAME = "wearmail_settings"

        private const val KEY_FOREGROUND_MINUTES = "foreground_sync_minutes"
        private const val KEY_BACKGROUND_MINUTES = "background_sync_minutes"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only_sync"
        private const val KEY_MAX_HEADERS = "max_cached_headers"
        private const val KEY_MAX_BODIES = "max_cached_bodies"
        private const val KEY_SIGNATURE = "signature"

        /** Microsoft OAuth2 客户端 ID / 租户（Outlook 账户授权用） */
        private const val KEY_MS_CLIENT_ID = "microsoft_oauth_client_id"
        private const val KEY_MS_TENANT = "microsoft_oauth_tenant"

        /** 被关闭通知的账户 id 集合（存字符串，Long 无 StringSet 支持） */
        private const val KEY_NOTIF_DISABLED_ACCOUNTS = "notif_disabled_accounts"
    }
}
