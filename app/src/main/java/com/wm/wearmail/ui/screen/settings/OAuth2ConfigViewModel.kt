package com.wm.wearmail.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.BuildConfig
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Outlook OAuth2 配置页 UI 状态。
 */
data class OAuth2ConfigUiState(
    /** 设置中填写的客户端 ID（空串表示未填写，运行时回落到构建期默认值） */
    val clientId: String = "",
    /** 租户（默认 common：个人与企业账号都支持） */
    val tenant: String = "common",
    /** 配置状态提示（是否可用、当前来源） */
    val statusText: String = "",
    /** 当前是否可用（设置值或构建期默认值任一非空） */
    val configured: Boolean = false,
)

/**
 * Outlook OAuth2 配置页 ViewModel（由 [OAuth2ConfigScreen] 使用）。
 *
 * 从设置页拆出（原逻辑在 SettingsViewModel）的原因：客户端 ID / 租户是低频
 * 一次性配置，独立成页后设置页只保留入口按钮；本页职责单一 —— 读写这两个字段，
 * 并给出「当前配置来源」的明确提示（设置值 > 构建期默认值 > 未配置）。
 *
 * 客户端 ID 不属于机密（公开客户端 + 设备码流，无客户端密码），
 * 但仍只写入应用私有存储，且不写日志。
 */
class OAuth2ConfigViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(OAuth2ConfigUiState())
    val uiState: StateFlow<OAuth2ConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                val fromSettings = settings.microsoftOAuthClientId
                val fallback = BuildConfig.MICROSOFT_OAUTH_CLIENT_ID
                val configured = fromSettings.isNotBlank() || fallback.isNotBlank()
                val status = when {
                    fromSettings.isNotBlank() -> "已配置：使用下方填写的客户端 ID（优先于内置默认值）"
                    fallback.isNotBlank() -> "已配置：使用构建期内置的默认客户端 ID"
                    else -> "未配置：Outlook 账户将无法完成授权"
                }
                _uiState.update {
                    it.copy(
                        clientId = fromSettings,
                        tenant = settings.microsoftOAuthTenant,
                        statusText = status,
                        configured = configured,
                    )
                }
            }
        }
    }

    /** 保存客户端 ID（输入即保存，与设置页既有行为一致） */
    fun setClientId(value: String) {
        viewModelScope.launch {
            runCatching {
                container.settings.update { it.copy(microsoftOAuthClientId = value.trim()) }
            }.onFailure { Logs.w(TAG, "保存 Microsoft 客户端 ID 失败") }
        }
    }

    /** 保存租户（留空表示使用 common） */
    fun setTenant(value: String) {
        viewModelScope.launch {
            runCatching {
                container.settings.update { it.copy(microsoftOAuthTenant = value.trim()) }
            }.onFailure { Logs.w(TAG, "保存 Microsoft 租户失败") }
        }
    }

    companion object {
        private const val TAG = "OAuth2Config"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { OAuth2ConfigViewModel(container) }
        }
    }
}
