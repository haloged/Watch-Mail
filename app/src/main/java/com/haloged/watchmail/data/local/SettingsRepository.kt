package com.haloged.watchmail.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 应用级 DataStore 单例（DataStore 必须全进程唯一）
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "watchmail_settings")

/**
 * 应用设置数据模型
 */
data class AppSettings(
    /** 同步频率（分钟）。WorkManager PeriodicWork 最小间隔为 15 分钟，低于 15 会自动钳制 */
    val syncFrequencyMinutes: Int = 15,
    /** 全局通知开关（每账户另有独立开关，两者都开才推送） */
    val notificationsEnabled: Boolean = true,
    /** 联网后自动补发离线草稿 */
    val autoSendDrafts: Boolean = true,
    /** 界面显示大小（百分比）。100 = 1.0x 默认；用于微调圆形表盘的适配效果 */
    val uiScalePercent: Int = 100
) {
    /** 界面缩放系数（1.0 = 默认大小） */
    val uiScale: Float get() = uiScalePercent / 100f
}

/**
 * 设置仓库（DataStore 持久化）
 * 用于存储同步频率、通知开关等全局偏好
 */
class SettingsRepository(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = SettingsRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        // 偏好键
        private val KEY_SYNC_FREQ = intPreferencesKey("sync_frequency_minutes")
        private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val KEY_AUTO_SEND_DRAFTS = booleanPreferencesKey("auto_send_drafts")
        private val KEY_UI_SCALE = intPreferencesKey("ui_scale_percent")

        /** 界面缩放范围（百分比），默认 100 */
        const val UI_SCALE_MIN = 70
        const val UI_SCALE_MAX = 130
        const val UI_SCALE_STEP = 5
        const val UI_SCALE_DEFAULT = 100
    }

    private val dataStore = context.settingsDataStore

    /** 设置 Flow，供 UI 观察 */
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            syncFrequencyMinutes = prefs[KEY_SYNC_FREQ] ?: 15,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: true,
            autoSendDrafts = prefs[KEY_AUTO_SEND_DRAFTS] ?: true,
            uiScalePercent = (prefs[KEY_UI_SCALE] ?: UI_SCALE_DEFAULT).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        )
    }

    /** 一次性读取当前设置（供 Worker 同步上下文使用） */
    suspend fun current(): AppSettings = settings.first()

    /** 更新同步频率（分钟），自动钳制在 15~180 分钟（WorkManager 限制） */
    suspend fun setSyncFrequency(minutes: Int) {
        val clamped = minutes.coerceIn(15, 180)
        dataStore.edit { it[KEY_SYNC_FREQ] = clamped }
    }

    /** 更新全局通知开关 */
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
    }

    /** 更新自动发送草稿开关 */
    suspend fun setAutoSendDrafts(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_SEND_DRAFTS] = enabled }
    }

    /** 更新界面显示大小（百分比），立即生效并持久化 */
    suspend fun setUiScalePercent(percent: Int) {
        val clamped = percent.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        dataStore.edit { it[KEY_UI_SCALE] = clamped }
    }
}
