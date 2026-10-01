package com.wm.wearmail.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.prefs.AppSettings
import com.wm.wearmail.data.prefs.SyncFrequency
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.SyncReason
import com.wm.wearmail.notify.NotificationDiagnostics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账户级通知开关（携带账户对象，便于直接展示别名）。
 */
data class AccountNotificationSetting(
    val account: Account,
    val enabled: Boolean,
)

/**
 * 设置页 UI 状态。
 *
 * @param accountNotifications 账户级通知开关（顺序与账户列表一致）
 * @param headerCount 本地缓存的邮件元数据条数
 * @param bodyCount 本地缓存的正文条数
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val accountNotifications: List<AccountNotificationSetting> = emptyList(),
    val headerCount: Int = 0,
    val bodyCount: Int = 0,
    val cacheBusy: Boolean = false,
    val syncing: Boolean = false,
    val syncMessage: String? = null,
    val lastSyncError: String? = null,
    val loading: Boolean = true,
    /**
     * 通知链路自检结果（进入页面、切换开关、点「测试通知」后刷新）。
     *
     * 放在 UiState 里是安全的：它只含开关状态与账户名，**不含任何凭据或邮件内容**。
     */
    val notifyDiagnostics: NotificationDiagnostics? = null,
)

/** 设置页一次性事件（振动反馈 + 顶部提示） */
sealed interface SettingsEvent {
    data class Message(val text: String, val success: Boolean) : SettingsEvent
}

/**
 * 设置页 ViewModel。
 *
 * 负责四组设置的读写与副作用：
 * 1. **同步**：前台频率同时决定后台 WorkManager 策略（仅手动 → 取消后台任务）；
 * 2. **通知**：全局开关 + 账户级开关（写入 SettingsStore 并同步账户表字段）；
 * 3. **存储**：缓存统计与一键清理（[com.wm.wearmail.data.repo.EmailRepository.enforceLimits]）；
 * 4. 立即同步：调用 [com.wm.wearmail.sync.SyncService.syncAll] 并汇总结果提示。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        // 全局设置（SharedPreferences 的内存快照流，首帧即有值）
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, loading = false) }
                // 开关变化后同步刷新通知自检结论（用户在设置页时结论必须是最新的）
                refreshNotifyDiagnostics()
            }
        }

        // 账户列表 → 刷新账户级通知开关
        viewModelScope.launch {
            container.accounts.accounts.collect { accounts ->
                val list = accounts.map { account ->
                    AccountNotificationSetting(
                        account = account,
                        enabled = container.settings.accountNotifications(account.id),
                    )
                }
                _uiState.update { it.copy(accountNotifications = list) }
            }
        }

        // 冷启动时账户快照可能还是空的，主动预热一次
        viewModelScope.launch {
            if (container.accounts.accounts.value.isEmpty()) {
                runCatching { container.accounts.load() }
                    .onFailure { Logs.w(TAG, "账户预热失败：${it.javaClass.simpleName}") }
            }
        }

        // 同步引擎状态（立即同步时展示进度）
        viewModelScope.launch {
            container.sync.state.collect { syncState ->
                _uiState.update {
                    it.copy(syncing = syncState.running, lastSyncError = syncState.lastError)
                }
            }
        }

        refreshCacheStats()
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /**
     * 设置前台同步频率，并让后台策略与之匹配。
     *
     * 「仅手动刷新」时必须取消后台周期任务，否则用户以为不自动同步、
     * 实际 WorkManager 仍在后台拉取，会产生「省电设置没生效」的错觉。
     */
    fun setSyncFrequency(frequency: SyncFrequency) {
        viewModelScope.launch {
            runCatching {
                container.settings.update { it.copy(foregroundSyncMinutes = frequency.minutes) }
                if (frequency == SyncFrequency.MANUAL) {
                    container.sync.cancelBackgroundSync()
                } else {
                    container.sync.scheduleBackgroundSync()
                }
            }.fold(
                onSuccess = {
                    Logs.i(TAG, "前台同步频率已更新为 ${frequency.label}")
                    _events.tryEmit(SettingsEvent.Message("已设置为${frequency.label}", success = true))
                },
                onFailure = {
                    Logs.w(TAG, "同步频率更新失败：${it.javaClass.simpleName}")
                    _events.tryEmit(SettingsEvent.Message("设置失败", success = false))
                },
            )
        }
    }

    /** 仅 Wi-Fi 同步开关 */
    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                container.settings.update { it.copy(wifiOnlySync = enabled) }
            }.isSuccess
            if (ok) {
                Logs.i(TAG, "仅 Wi-Fi 同步：$enabled")
                _events.tryEmit(
                    SettingsEvent.Message(if (enabled) "已开启仅 Wi-Fi 同步" else "已关闭仅 Wi-Fi 同步", true),
                )
            } else {
                _events.tryEmit(SettingsEvent.Message("设置失败", false))
            }
        }
    }

    /** 立即同步全部账户 */
    fun syncNow() {
        if (_uiState.value.syncing) return
        viewModelScope.launch {
            val result = runCatching { container.sync.syncAll(SyncReason.MANUAL) }
            result.fold(
                onSuccess = { report ->
                    Logs.i(TAG, "手动同步完成：成功 ${report.successCount} 个账户，新增 ${report.newMailCount} 封")
                    val text = when {
                        report.results.isEmpty() -> "没有可同步的账户"
                        report.failedCount > 0 ->
                            "新增 ${report.newMailCount} 封，${report.failedCount} 个账户失败"
                        else -> "同步完成，新增 ${report.newMailCount} 封"
                    }
                    _uiState.update { it.copy(syncMessage = text) }
                    _events.tryEmit(SettingsEvent.Message(text, success = report.failedCount == 0))
                },
                onFailure = { t ->
                    Logs.w(TAG, "手动同步失败：${t.javaClass.simpleName}")
                    _uiState.update { it.copy(syncMessage = "同步失败，请检查网络") }
                    _events.tryEmit(SettingsEvent.Message("同步失败，请检查网络", success = false))
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /** 全局通知开关 */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                container.settings.update { it.copy(notificationsEnabled = enabled) }
            }.isSuccess
            if (ok) {
                Logs.i(TAG, "全局通知开关：$enabled")
                _events.tryEmit(
                    SettingsEvent.Message(if (enabled) "已开启通知" else "已关闭通知", true),
                )
            } else {
                _events.tryEmit(SettingsEvent.Message("设置失败", false))
            }
        }
    }

    /** 账户级通知开关：同时写 SettingsStore 与账户表，避免两处显示不一致 */
    fun setAccountNotifications(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                container.settings.setAccountNotifications(accountId, enabled)
                container.accounts.setNotificationsEnabled(accountId, enabled)
            }.isSuccess
            if (ok) {
                _uiState.update { current ->
                    current.copy(
                        accountNotifications = current.accountNotifications.map { item ->
                            if (item.account.id == accountId) item.copy(enabled = enabled) else item
                        },
                    )
                }
                Logs.i(TAG, "账户通知开关：id=$accountId enabled=$enabled")
                _events.tryEmit(SettingsEvent.Message(if (enabled) "已开启该账户通知" else "已关闭该账户通知", true))
            } else {
                _events.tryEmit(SettingsEvent.Message("设置失败", false))
            }
        }
    }

    // ------------------------------------------------------------------
    // 存储
    // ------------------------------------------------------------------

    /** 刷新缓存统计（进入设置页时调用） */
    fun refreshCacheStats() {
        viewModelScope.launch {
            val headers = runCatching { container.emails.totalCount() }.getOrDefault(0)
            val bodies = runCatching { container.emails.bodyCount() }.getOrDefault(0)
            _uiState.update { it.copy(headerCount = headers, bodyCount = bodies) }
        }
    }

    /**
     * 清理缓存。
     *
     * `enforceLimits(0, 0)` 表示把上限压到 0，即淘汰全部可淘汰的缓存
     * （未读邮件等受保护数据由仓储实现决定），返回本次淘汰条数。
     */
    fun clearCache() {
        if (_uiState.value.cacheBusy) return
        _uiState.update { it.copy(cacheBusy = true) }
        viewModelScope.launch {
            val evicted = runCatching { container.emails.enforceLimits(0, 0) }.getOrDefault(-1)
            _uiState.update { it.copy(cacheBusy = false) }
            if (evicted < 0) {
                Logs.w(TAG, "清理缓存失败")
                _events.tryEmit(SettingsEvent.Message("清理失败，请稍后重试", success = false))
            } else {
                Logs.i(TAG, "已清理缓存，淘汰 $evicted 条")
                _events.tryEmit(SettingsEvent.Message("已清理 $evicted 条缓存", success = true))
            }
            refreshCacheStats()
        }
    }

    // ------------------------------------------------------------------
    // 通知自检与测试通知
    // ------------------------------------------------------------------

    /** 刷新通知链路自检结论（失败不影响页面可用，只是不显示结论） */
    fun refreshNotifyDiagnostics() {
        val diagnostics = runCatching { container.notifications.diagnose() }.getOrNull()
        _uiState.update { it.copy(notifyDiagnostics = diagnostics) }
    }

    /**
     * 发出测试通知并刷新自检结论。
     *
     * 测试通知与真实邮件共用投递路径，但**绕过应用内总开关** ——
     * 目的是把"系统不允许"与"应用内被关掉"两种情况区分开：
     * 前者发不出去（提示去系统设置），后者能发出去且自检结论会明确说明真实邮件会被拦。
     */
    fun testNotification() {
        refreshNotifyDiagnostics()
        val posted = runCatching { container.notifications.notifyTest() }.getOrDefault(false)
        Logs.i(TAG, "测试通知：${if (posted) "已提交系统" else "未提交（系统未允许）"}")
        _events.tryEmit(
            if (posted) {
                SettingsEvent.Message("已发出测试通知，请抬腕查看", success = true)
            } else {
                SettingsEvent.Message("未能发出：系统未允许本应用发送通知", success = false)
            },
        )
    }

    // ------------------------------------------------------------------
    // Outlook OAuth2（Microsoft 账号授权）
    // ------------------------------------------------------------------

    /**
     * 保存 Microsoft OAuth2 客户端 ID（Azure 应用注册的 Application ID）。
     *
     * 为什么让用户在手表上填：Azure 应用必须由使用者自己注册（没人能替他注册），
     * 运行时填写比"改 gradle.properties 重新打包"现实得多。客户端 ID 不属于机密，
     * 但仍只写入应用私有存储，且不写日志。
     */
    fun setMicrosoftClientId(value: String) {
        viewModelScope.launch {
            runCatching {
                container.settings.update { it.copy(microsoftOAuthClientId = value.trim()) }
            }.onFailure { Logs.w(TAG, "保存 Microsoft 客户端 ID 失败") }
        }
    }

    /** 保存 Microsoft OAuth2 租户（留空表示使用 common：个人与企业账号都支持） */
    fun setMicrosoftTenant(value: String) {
        viewModelScope.launch {
            runCatching {
                container.settings.update { it.copy(microsoftOAuthTenant = value.trim()) }
            }.onFailure { Logs.w(TAG, "保存 Microsoft 租户失败") }
        }
    }

    companion object {
        private const val TAG = "Settings"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
