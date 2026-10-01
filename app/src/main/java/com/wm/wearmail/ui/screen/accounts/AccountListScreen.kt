package com.wm.wearmail.ui.screen.accounts

import androidx.compose.foundation.layout.Arrangement
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
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.RotaryBus
import com.wm.wearmail.model.Account
import com.wm.wearmail.ui.kit.AccountInitialBadge
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.ConfirmDialog
import com.wm.wearmail.ui.kit.EmptyState
import com.wm.wearmail.ui.kit.ErrorBanner
import com.wm.wearmail.ui.kit.LoadingIndicator
import com.wm.wearmail.ui.kit.PrimaryActionButton
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.WearTextField
import com.wm.wearmail.ui.kit.rememberHaptics
import com.wm.wearmail.util.TimeFormat

/** 文本输入框最小高度：保证触控热区不小于 48dp */
private val TOUCH_TARGET_MIN_HEIGHT = 48.dp

/**
 * 账户管理页（一级页面，位于横向分页的第三页）。
 *
 * 交互约定：
 * - **单击**账户行 → 弹出操作菜单（重命名 / 通知开关 / 删除）；
 * - **长按**账户行 → 同一个菜单（手表端长按比单击更不容易误触，两种都提供）；
 * - 底部主按钮 → 添加账户（覆盖层）。
 *
 * 圆形表盘适配：列表用 [CircularSafeLazyColumn]，每一项都包在 [CircularItem] 中，
 * 索引必须等于该项在 LazyColumn 中的真实位置，否则弦长计算会取错 Y 区间。
 * 这里通过 [circularSlot] + 自增计数器保证「注册顺序 == 索引」。
 */
@Composable
fun AccountListScreen(
    container: AppContainer,
    active: Boolean,
    rotaryBus: RotaryBus,
    onAddAccount: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: AccountListViewModel = viewModel(factory = AccountListViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    // 顶部一次性提示（重命名成功 / 删除失败等）
    var message by remember { mutableStateOf<String?>(null) }
    // 操作菜单的目标账户
    var menuAccount by remember { mutableStateOf<Account?>(null) }
    // 重命名对话框的目标账户
    var renameAccount by remember { mutableStateOf<Account?>(null) }
    // 删除二次确认的目标账户
    var deleteAccount by remember { mutableStateOf<Account?>(null) }

    // 页面回到前台时刷新一次（新增账户后及时出现）
    LaunchedEffect(active) {
        if (active) vm.refresh()
    }

    // 操作结果 → 振动 + 文案提示
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AccountListEvent.Message -> {
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
            // 计数器：与 LazyColumn 的真实 item 索引严格一致
            var index = 0

            // ---------------- 顶部：返回 + 标题 + 提示 ----------------
            circularSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            haptics.tick()
                            onBack()
                        }) {
                            Icon(
                                imageVector = WearIcons.Back,
                                contentDescription = "返回收件箱",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "邮箱账户",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (message != null) {
                        Text(
                            text = message.orEmpty(),
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

            // ---------------- 加载 / 错误 / 空态 ----------------
            if (state.loading) {
                circularSlot(index++, listState) { modifier ->
                    Row(
                        modifier = modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            state.error?.let { errorText ->
                circularSlot(index++, listState) { modifier ->
                    ErrorBanner(
                        message = errorText,
                        modifier = modifier,
                        onRetry = { vm.refresh() },
                    )
                }
            }

            if (state.isEmpty) {
                circularSlot(index++, listState) { modifier ->
                    EmptyState(
                        icon = WearIcons.Account,
                        title = "还没有邮箱账户",
                        hint = "点击下方「添加账户」，或用手机扫码快速配置",
                        modifier = modifier,
                    )
                }
            }

            // ---------------- 账户行 ----------------
            state.rows.forEach { row ->
                circularSlot(index++, listState, key = "account-${row.account.id}") { modifier ->
                    AccountRowCard(
                        row = row,
                        modifier = modifier,
                        onClick = {
                            haptics.tick()
                            menuAccount = row.account
                        },
                        onLongClick = {
                            haptics.longPress()
                            menuAccount = row.account
                        },
                    )
                }
            }

            // ---------------- 底部主按钮 ----------------
            circularSlot(index, listState) { modifier ->
                PrimaryActionButton(
                    text = "添加账户",
                    onClick = {
                        haptics.tick()
                        onAddAccount()
                    },
                    modifier = modifier.fillMaxWidth(),
                    icon = WearIcons.Add,
                )
            }
        }
    }

    // ---------------- 操作菜单 ----------------
    // 注意：Wear M3 的 AlertDialog 内部是 ScalingLazyColumn，每个 item 都不得高于可视区，
    // 因此菜单项必须逐条放进 content 槽（而不是塞进 text 槽的一个大 Column）。
    val currentMenu = menuAccount
    AlertDialog(
        visible = currentMenu != null,
        onDismissRequest = { menuAccount = null },
        title = { Text(text = currentMenu?.displayLabel ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(
                text = currentMenu?.email ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            item {
                Button(
                    onClick = {
                        haptics.tick()
                        renameAccount = currentMenu
                        menuAccount = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = WearIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "重命名", maxLines = 1)
                }
            }
            item {
                // 通知开关：SwitchButton 自带 ToggleOn/Off 振动
                SwitchButton(
                    checked = currentMenu?.notificationsEnabled == true,
                    onCheckedChange = { enabled ->
                        currentMenu?.let { vm.setNotificationsEnabled(it, enabled) }
                        menuAccount = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "新邮件通知", maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = {
                        haptics.tick()
                        deleteAccount = currentMenu
                        menuAccount = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = WearIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "删除账户", maxLines = 1)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                haptics.tick()
                menuAccount = null
            }) {
                Text(text = "关闭")
            }
        },
    )

    // ---------------- 重命名 ----------------
    val currentRename = renameAccount
    if (currentRename != null) {
        // 输入框状态随对话框目标账户初始化
        var aliasInput by remember(currentRename.id) { mutableStateOf(currentRename.alias) }
        AlertDialog(
            visible = true,
            onDismissRequest = { renameAccount = null },
            title = { Text(text = "账户别名") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "别名只影响本机显示，例如「工作」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    WearTextField(
                        modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        placeholder = "例如：工作",
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(currentRename, aliasInput)
                    renameAccount = null
                }) {
                    Text(text = "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameAccount = null }) {
                    Text(text = "取消")
                }
            },
        )
    }

    // ---------------- 删除二次确认 ----------------
    ConfirmDialog(
        visible = deleteAccount != null,
        title = "删除该账户？",
        message = deleteAccount?.let { "将同时清理「${it.displayLabel}」在本机缓存的全部邮件与草稿，此操作不可恢复。" },
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            deleteAccount?.let { vm.delete(it) }
            deleteAccount = null
        },
        onDismiss = { deleteAccount = null },
    )
}

/**
 * 账户行卡片。
 *
 * 信息层级（466x466 上一次只能显示 2~3 行）：
 * 首行 = 首字母徽标 + 邮箱地址；次行 = 别名 + 未读数 + 最近同步时间。
 */
@Composable
private fun AccountRowCard(
    row: AccountListRow,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val account = row.account
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        onLongClick = onLongClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountInitialBadge(label = account.displayLabel, colorIndex = account.colorIndex)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.alias.ifBlank { "未设置别名" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (row.unreadCount > 0) "${row.unreadCount} 封未读" else "无未读",
                style = MaterialTheme.typography.labelSmall,
                color = if (row.unreadCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = TimeFormat.relativeTime(account.lastSyncAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 圆形安全列表项注册器。
 *
 * [index] 必须是该项在 LazyColumn 中的真实索引，因此调用处一律使用
 * 自增计数器（`circularSlot(index++, listState) { ... }`）。
 */
private fun LazyListScope.circularSlot(
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
