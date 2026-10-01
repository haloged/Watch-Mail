package com.wm.wearmail.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.wm.wearmail.BuildConfig
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.ui.kit.CompactOptionGroupOf
import com.wm.wearmail.core.RotaryBus
import com.wm.wearmail.data.prefs.SyncFrequency
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.ConfirmDialog
import com.wm.wearmail.ui.kit.LoadingIndicator
import com.wm.wearmail.ui.kit.PrimaryActionButton
import com.wm.wearmail.ui.kit.SectionHeader
import com.wm.wearmail.ui.kit.SecondaryActionButton
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.WearTextField
import com.wm.wearmail.ui.kit.rememberHaptics

/**
 * 文本框的最小高度：需求要求触控热区不小于 48x48dp，
 * 而 [WearTextField] 按内容高度测量（约 36dp），因此调用处统一补高度下限。
 */
private val SETTINGS_FIELD_MIN_HEIGHT = 48.dp

/**
 * 设置页（一级页面，位于横向分页的第一页 —— 主界面右滑进入）。
 *
 * 分组：同步 / 通知 / Outlook OAuth2 / 存储 / 关于。
 * 全部操作都写回 [com.wm.wearmail.data.prefs.SettingsStore]，并在需要时
 * 联动后台任务（同步频率 → WorkManager）与账户表（账户级通知开关）。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    active: Boolean,
    rotaryBus: RotaryBus,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    // 顶部一次性提示
    var message by remember { mutableStateOf<String?>(null) }
    // 清理缓存二次确认
    var confirmClear by remember { mutableStateOf(false) }

    // 回到本页时刷新缓存统计（缓存会随同步变化）
    LaunchedEffect(active) {
        if (active) vm.refreshCacheStats()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is SettingsEvent.Message -> {
                    message = event.text
                    if (event.success) haptics.success() else haptics.error()
                }
            }
        }
    }

    CircularScreen {
        CircularSafeLazyColumn(
            state = listState,
            active = active,
            rotaryBus = rotaryBus,
        ) {
            var index = 0

            // ---------------- 标题 ----------------
            settingsSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    Text(
                        text = "设置",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    message?.let { text ->
                        Text(
                            text = text,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ---------------- 同步 ----------------
            settingsSlot(index++, listState) { modifier ->
                SectionHeader(text = "同步", modifier = modifier)
            }

            // 4 个频率档位放在同一个列表项里自适应换行。
            // 原来是「一档一条整行 52dp 大按钮」，4 条就占掉半屏且视觉笨重。
            settingsSlot(index++, listState, key = "freq-group") { modifier ->
                CompactOptionGroupOf(
                    labels = SyncFrequency.entries.map { it.label },
                    selectedIndex = SyncFrequency.entries.indexOfFirst {
                        it.minutes == state.settings.foregroundSyncMinutes
                    },
                    onSelect = { position ->
                        val frequency = SyncFrequency.entries[position]
                        haptics.tick()
                        vm.setSyncFrequency(frequency)
                    },
                    modifier = modifier,
                )
            }

            settingsSlot(index++, listState) { modifier ->
                Column(
                    modifier = modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrimaryActionButton(
                        text = if (state.syncing) "同步中…" else "立即同步",
                        onClick = {
                            haptics.tick()
                            vm.syncNow()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = WearIcons.Refresh,
                        enabled = !state.syncing,
                    )
                    if (state.syncing) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LoadingIndicator()
                    }
                    state.syncMessage?.let { text ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.lastSyncError?.let { errorText ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            settingsSlot(index++, listState) { modifier ->
                SwitchButton(
                    checked = state.settings.wifiOnlySync,
                    onCheckedChange = { vm.setWifiOnly(it) },
                    modifier = modifier.fillMaxWidth(),
                    secondaryLabel = { Text(text = "省电、省流量", maxLines = 1) },
                    label = { Text(text = "仅 Wi-Fi 同步", maxLines = 1) },
                )
            }

            // ---------------- 通知 ----------------
            settingsSlot(index++, listState) { modifier ->
                SectionHeader(text = "通知", modifier = modifier)
            }

            settingsSlot(index++, listState) { modifier ->
                SwitchButton(
                    checked = state.settings.notificationsEnabled,
                    onCheckedChange = { vm.setNotificationsEnabled(it) },
                    modifier = modifier.fillMaxWidth(),
                    secondaryLabel = { Text(text = "关闭后不再提醒新邮件", maxLines = 1) },
                    label = { Text(text = "新邮件通知", maxLines = 1) },
                )
            }

            // 通知自检 + 测试通知。
            // 收不到通知的原因可能在三层：应用内总开关 / 系统权限与渠道 / 账户级开关，
            // 用户只能看到"没通知"。这里先给出结论，再提供一键测试（与真实邮件同一投递路径）。
            settingsSlot(index++, listState, key = "notify-test") { modifier ->
                Column(
                    modifier = modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    state.notifyDiagnostics?.let { diagnostics ->
                        Text(
                            text = diagnostics.advice(),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (diagnostics.allGood) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    SecondaryActionButton(
                        text = "测试通知",
                        icon = WearIcons.Notifications,
                        onClick = {
                            haptics.tick()
                            vm.testNotification()
                        },
                    )
                }
            }

            if (state.accountNotifications.isEmpty()) {
                settingsSlot(index++, listState) { modifier ->
                    Text(
                        text = "还没有邮箱账户",
                        modifier = modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            } else {
                state.accountNotifications.forEach { item ->
                    settingsSlot(index++, listState, key = "notify-${item.account.id}") { modifier ->
                        SwitchButton(
                            checked = item.enabled,
                            onCheckedChange = { vm.setAccountNotifications(item.account.id, it) },
                            modifier = modifier.fillMaxWidth(),
                            secondaryLabel = {
                                Text(
                                    text = item.account.email,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            label = {
                                Text(
                                    text = "${item.account.displayLabel} 的通知",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            // ---------------- Outlook OAuth2 ----------------
            // Outlook 已停用 IMAP/SMTP 基础认证，必须用 OAuth2；客户端 ID 需要用户
            // 自己在 Azure 门户注册应用后填入（设备码流不需要客户端密码）。
            settingsSlot(index++, listState) { modifier ->
                SectionHeader(text = "Outlook OAuth2", modifier = modifier)
            }

            settingsSlot(index++, listState, key = "ms-oauth-fields") { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    WearTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SETTINGS_FIELD_MIN_HEIGHT),
                        value = state.settings.microsoftOAuthClientId,
                        onValueChange = vm::setMicrosoftClientId,
                        placeholder = "客户端 ID（Application ID）",
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    WearTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SETTINGS_FIELD_MIN_HEIGHT),
                        value = state.settings.microsoftOAuthTenant,
                        onValueChange = vm::setMicrosoftTenant,
                        placeholder = "租户（默认 common）",
                        singleLine = true,
                    )
                }
            }

            settingsSlot(index++, listState) { modifier ->
                Text(
                    text = "在 Azure 门户「应用注册」新建应用：账户类型选「任何组织目录中的账户和" +
                        "个人 Microsoft 账户」，并在「身份验证」里启用「允许公共客户端流」；" +
                        "把「应用程序(客户端) ID」填到上面。公共客户端 + 设备码流不需要客户端密码。",
                    modifier = modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---------------- 存储 ----------------
            settingsSlot(index++, listState) { modifier ->
                SectionHeader(text = "存储", modifier = modifier)
            }

            settingsSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    InfoLine(text = "邮件缓存：${state.headerCount} 条")
                    InfoLine(text = "正文缓存：${state.bodyCount} 条")
                    Spacer(modifier = Modifier.height(2.dp))
                    PrimaryActionButton(
                        text = if (state.cacheBusy) "清理中…" else "清理缓存",
                        onClick = {
                            haptics.tick()
                            confirmClear = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = WearIcons.Delete,
                        enabled = !state.cacheBusy,
                    )
                }
            }

            // ---------------- 关于 ----------------
            settingsSlot(index++, listState) { modifier ->
                SectionHeader(text = "关于", modifier = modifier)
            }

            settingsSlot(index++, listState) { modifier ->
                Column(
                    modifier = modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = WearIcons.Mail,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "腕邮 WearMail",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    InfoLine(text = "版本 ${BuildConfig.VERSION_NAME}")
                    InfoLine(text = "已适配 466×466 圆形表盘，列表按弦长自动收窄")
                    InfoLine(text = "邮箱密码使用 Android Keystore AES-256-GCM 加密存储")
                }
            }
        }
    }

    ConfirmDialog(
        visible = confirmClear,
        title = "清理本地缓存？",
        message = "将删除已下载的邮件与正文缓存（云端邮件不受影响），下次打开时需重新下载。",
        confirmText = "清理",
        dismissText = "取消",
        onConfirm = {
            confirmClear = false
            vm.clearCache()
        },
        onDismiss = { confirmClear = false },
    )
}

/** 说明性文字行（居中、次要色，正文不小于 14sp 由 labelSmall/bodySmall 保证） */
@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 圆形安全列表项注册器（[index] 必须等于该项在 LazyColumn 中的真实索引）。
 */
private fun LazyListScope.settingsSlot(
    index: Int,
    listState: LazyListState,
    key: Any? = null,
    content: @Composable (Modifier) -> Unit,
) {
    item(key = key) {
        CircularItem(index = index, state = listState) { rowModifier ->
            content(rowModifier)
        }
    }
}
