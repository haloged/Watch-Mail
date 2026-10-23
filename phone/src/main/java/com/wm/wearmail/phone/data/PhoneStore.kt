package com.wm.wearmail.phone.data

import android.content.Context
import android.content.SharedPreferences
import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.crypto.AndroidKeystoreKeyProvider
import com.wm.wearmail.data.crypto.CryptoManagerImpl
import com.wm.wearmail.model.OAuthTokens
import org.json.JSONObject

/**
 * 手机端本地存储。
 *
 * - **账户配置**：整份 JSON 经 Android Keystore（AES-256-GCM）加密后存 SharedPreferences，
 *   绝不明文落盘（与手表端同一套加密实现）；
 * - **OAuth2 客户端 ID**：随同步配置一并存入，用于手机端自行刷新令牌；
 * - **已通知 UID**：每账户记录「已发过通知的最大 UID」，避免同一封邮件反复通知；
 * - **通知开关 / 同步间隔 / 使用说明状态**：用户偏好。
 */
class PhoneStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val crypto = CryptoManagerImpl(AndroidKeystoreKeyProvider(appContext))

    /**
     * 保存一次「扫码同步」的完整结果：账户列表 + OAuth2 客户端 ID。
     *
     * @return 解析出的账户列表（格式异常时为空列表；已在内部记日志）
     */
    fun saveConfig(json: String): List<SyncedAccount> {
        val clientId = runCatching { JSONObject(json).optString("oauthClientId") }.getOrDefault("")
        if (clientId.isNotBlank()) {
            prefs.edit().putString(KEY_OAUTH_CLIENT, clientId).apply()
        }
        val accounts = SyncedAccount.parseConfig(json).orEmpty()
        saveAccounts(accounts)
        return accounts
    }

    fun saveAccounts(accounts: List<SyncedAccount>) {
        val payload = crypto.encrypt(SyncedAccount.toJson(accounts))
        prefs.edit().putString(KEY_ACCOUNTS, payload).apply()
        Logs.i(TAG, "已保存 ${accounts.size} 个同步账户（加密）")
    }

    /** 读取账户；从未同步或解密失败时返回空列表（失败会记日志，不抛异常） */
    fun loadAccounts(): List<SyncedAccount> {
        val payload = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val plain = crypto.decrypt(payload)
        if (plain == null) {
            Logs.w(TAG, "账户配置解密失败（Keystore 密钥可能已失效），需重新扫码同步")
            return emptyList()
        }
        return SyncedAccount.parseConfig(plain).orEmpty()
    }

    /** OAuth2 客户端 ID（来自手表设置，随配置同步；空表示手表未配置） */
    fun oauthClientId(): String = prefs.getString(KEY_OAUTH_CLIENT, "").orEmpty()

    /** 刷新成功后回写某账户的令牌组（重新加密保存） */
    fun updateOauth(email: String, tokens: OAuthTokens) {
        val accounts = loadAccounts().map { account ->
            if (account.email.equals(email, ignoreCase = true)) {
                account.copy(oauth = tokens)
            } else {
                account
            }
        }
        saveAccounts(accounts)
    }

    /** 该账户已发过通知的最大 UID；0 表示尚未建立基线 */
    fun lastSeenUid(email: String): Long =
        prefs.getLong(KEY_UID_PREFIX + email.lowercase(), 0L)

    fun setLastSeenUid(email: String, uid: Long) {
        prefs.edit().putLong(KEY_UID_PREFIX + email.lowercase(), uid).apply()
    }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()
        }

    /** 自动同步间隔（分钟），默认 1 分钟 */
    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceIn(1, 60)
        set(value) {
            prefs.edit().putInt(KEY_SYNC_INTERVAL, value.coerceIn(1, 60)).apply()
        }

    /** 首次启动的使用说明是否已读 */
    var guideShown: Boolean
        get() = prefs.getBoolean(KEY_GUIDE_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GUIDE_SHOWN, value).apply()
        }

    /** 清除全部同步数据（停止服务时使用） */
    fun clear() {
        val editor = prefs.edit().remove(KEY_ACCOUNTS).remove(KEY_OAUTH_CLIENT)
        // 一并清掉所有 lastSeenUid 键
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_UID_PREFIX)) editor.remove(key)
        }
        editor.apply()
        Logs.i(TAG, "已清除同步账户")
    }

    companion object {
        private const val TAG = "PhoneStore"
        private const val PREFS_NAME = "wearmail_phone"
        private const val KEY_ACCOUNTS = "accounts_v1"
        private const val KEY_OAUTH_CLIENT = "oauth_client_id"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_UID_PREFIX = "last_uid_"
        private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        private const val KEY_GUIDE_SHOWN = "guide_shown"

        /** 默认同步间隔（分钟） */
        const val DEFAULT_INTERVAL_MINUTES = 1

        /** 可选的同步间隔（分钟） */
        val INTERVAL_OPTIONS = listOf(1, 5, 15)
    }
}
