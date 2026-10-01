package com.wm.wearmail.data.prefs

import kotlinx.coroutines.flow.StateFlow

/**
 * 同步频率档位。
 *
 * @param minutes 间隔分钟数，-1 表示不自动同步（仅手动）
 */
enum class SyncFrequency(val minutes: Int, val label: String) {
    FIVE(5, "每 5 分钟"),
    FIFTEEN(15, "每 15 分钟"),
    THIRTY(30, "每 30 分钟"),
    MANUAL(-1, "仅手动刷新"),
    ;

    companion object {
        fun fromMinutes(minutes: Int): SyncFrequency =
            entries.firstOrNull { it.minutes == minutes } ?: FIFTEEN
    }
}

/**
 * 应用设置（全局，非账户级）。
 *
 * 缓存上限对应需求：元数据最多 500 封、正文最多 50 封，超出按 LRU 淘汰。
 */
data class AppSettings(
    /** 前台自动同步间隔（分钟） */
    val foregroundSyncMinutes: Int = 5,
    /** 后台自动同步间隔（分钟），需 >= 15 以符合系统功耗限制 */
    val backgroundSyncMinutes: Int = 15,
    /** 全局通知开关 */
    val notificationsEnabled: Boolean = true,
    /** 仅在 Wi-Fi 下同步（省电/省流量） */
    val wifiOnlySync: Boolean = false,
    /** 邮件元数据缓存上限 */
    val maxCachedHeaders: Int = 500,
    /** 正文缓存上限 */
    val maxCachedBodies: Int = 50,
    /** 默认签名，追加在正文末尾 */
    val signature: String = "",
    /**
     * Microsoft (Outlook / Office 365) OAuth2 客户端 ID。
     *
     * 必须由使用者自己在 Azure 门户注册应用后获得（没人能替你注册），
     * 因此做成可运行时填写：设置页填的值优先于构建期注入的默认值。
     */
    val microsoftOAuthClientId: String = "",
    /** Microsoft OAuth2 租户：默认 `common`（同时支持个人账号与企业账号） */
    val microsoftOAuthTenant: String = "common",
) {
    /** 后台间隔必须落在系统允许的 15 分钟以上 */
    val normalizedBackgroundMinutes: Int
        get() = backgroundSyncMinutes.coerceAtLeast(15)
}

/**
 * 设置读写门面。
 *
 * 实现基于 SharedPreferences（同步读 + 内存 StateFlow 缓存），
 * 保证 UI 首帧即可拿到设置值，避免冷启动阻塞。
 */
interface SettingsStore {

    /** 当前设置（可观察，UI 用 collectAsStateWithLifecycle 订阅） */
    val settings: StateFlow<AppSettings>

    /** 原子更新设置，返回更新后的值 */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings

    /** 查询某账户的通知开关（默认开启） */
    fun accountNotifications(accountId: Long): Boolean

    /** 设置某账户的通知开关 */
    suspend fun setAccountNotifications(accountId: Long, enabled: Boolean)
}
