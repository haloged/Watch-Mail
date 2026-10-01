package com.wm.wearmail.ui.screen.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.RotaryBus
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.ui.kit.AccountDot
import com.wm.wearmail.ui.kit.AccountInitialBadge
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.ConfirmDialog
import com.wm.wearmail.ui.kit.EmptyState
import com.wm.wearmail.ui.kit.ErrorBanner
import com.wm.wearmail.ui.kit.MiniActionButton
import com.wm.wearmail.ui.kit.MiniOptionButton
import com.wm.wearmail.ui.kit.PrimaryActionButton
import com.wm.wearmail.ui.kit.UnreadDot
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.rememberBatteryPercent
import com.wm.wearmail.ui.kit.rememberHaptics
import com.wm.wearmail.util.TimeFormat

/**
 * 统一收件箱（首页中间页）。
 *
 * 布局（466x466px 圆形表盘，全部内容限制在半径 210px 的安全圆内）：
 * ```
 *  顶部弧形区：TimeText（由 CircularScreen 提供）+ 电量 / 同步状态
 *  账户筛选器：横向滚动的「全部 + 各账户」按钮
 *  邮件列表：CircularSafeLazyColumn（按弦长动态收窄，不被圆弧裁切）
 *  底部弧形区：「新建邮件」主操作按钮
 * ```
 *
 * 状态收集遵循项目统一写法：[ViewModel] + `collectAsStateWithLifecycle`。
 */
@Composable
fun InboxScreen(
    container: AppContainer,
    active: Boolean,
    rotaryBus: RotaryBus,
    onOpenEmail: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: InboxViewModel = viewModel(factory = InboxViewModel.factory(container))
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    // 同步状态变化时给出振动反馈：完成成功 = 两下短振，失败 = 长振
    var wasSyncing by remember { mutableStateOf(uiState.syncing) }
    LaunchedEffect(uiState.syncing) {
        if (wasSyncing && !uiState.syncing) {
            if (uiState.error == null) haptics.success() else haptics.error()
        }
        wasSyncing = uiState.syncing
    }

    // 当前筛选的账户若已被删除，自动回退到「全部」
    LaunchedEffect(uiState.accounts, uiState.selectedAccountId) {
        val selected = uiState.selectedAccountId
        if (selected != null && uiState.accounts.none { it.id == selected }) {
            vm.selectAccount(null)
        }
    }

    CircularScreen(modifier = Modifier.fillMaxSize()) {
        // 列表铺满整屏；顶部/底部弧形区以覆盖层形式叠在其上
        InboxList(
            uiState = uiState,
            listState = listState,
            active = active,
            rotaryBus = rotaryBus,
            onRefresh = { vm.refresh() },
            onRetry = { vm.refresh() },
            onDismissError = { vm.clearError() },
            onOpenEmail = onOpenEmail,
            onMarkRead = { id, read -> vm.markRead(id, read) },
            onSetFlagged = { id, flagged -> vm.setFlagged(id, flagged) },
            onDelete = { id -> vm.delete(id) },
            onOpenAccounts = onOpenAccounts,
            onOpenSettings = onOpenSettings,
        )

        // ---------------- 顶部弧形区：筛选器 ----------------
        // 背景用「透明 → 表盘底色」的渐变遮罩：邮件列表向上滚动时会被渐隐，
        // 保证筛选按钮上的文字始终可读，同时不会出现生硬的横向切边。
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(TOP_SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(top = FILTER_TOP_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccountFilterRow(
                accounts = uiState.accounts,
                selectedAccountId = uiState.selectedAccountId,
                unreadCounts = uiState.unreadCounts,
                onSelect = { id ->
                    haptics.tick()
                    vm.selectAccount(id)
                },
            )
        }

        // ---------------- 底部弧形区：新建邮件 ----------------
        // 只保留一个**迷你主按钮**（高度 24dp，约为常规按钮的一半）；
        // 状态信息已移到列表尾项，不再固定占用纵向空间。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(top = 6.dp, bottom = BOTTOM_BAR_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            // 仅同步中才出现一行提示，空闲时完全不占高度
            if (uiState.syncing) {
                Text(
                    text = "同步中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            MiniActionButton(
                text = "新建邮件",
                icon = WearIcons.Edit,
                onClick = {
                    haptics.tick()
                    onCompose()
                },
            )
        }
    }
}

/**
 * 邮件列表主体。
 *
 * `CircularSafeLazyColumn` 会把列表铺满整屏，并按每项在屏幕中的 Y 区间动态收窄宽度，
 * 因此上下内容内边距直接取遮罩高度：列表内容始终从筛选器下方开始、
 * 在操作栏上方结束，滚动时不会与覆盖层叠字。
 *
 * 索引通过 [circularSlot] 的 self-increment 计数器维护，
 * 保证与 LazyColumn 的真实 item 索引严格一致（否则弦长会取错 Y 区间）。
 */
@Composable
private fun InboxList(
    uiState: InboxUiState,
    listState: LazyListState,
    active: Boolean,
    rotaryBus: RotaryBus,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onOpenEmail: (Long) -> Unit,
    onMarkRead: (Long, Boolean) -> Unit,
    onSetFlagged: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isEmptyInbox = uiState.accounts.isNotEmpty() && uiState.mails.isEmpty()

    CircularSafeLazyColumn(
        state = listState,
        // 上下内边距分别等于顶部/底部渐隐遮罩高度：
        // 列表内容始终从遮罩下方开始、在遮罩上方结束，不会与筛选器或操作栏重叠。
        contentPadding = PaddingValues(top = TOP_SCRIM_HEIGHT, bottom = BOTTOM_SCRIM_HEIGHT),
        active = active,
        rotaryBus = rotaryBus,
        onPullToRefresh = onRefresh,
        isRefreshing = uiState.syncing,
    ) {
        // 计数器：与 LazyColumn 的真实 item 索引严格一致
        var index = 0

        // 标题：把当前筛选与未读总数放在列表首项，随列表一起滚动
        circularSlot(index++, listState, key = "header") { rowModifier ->
            ListHeader(modifier = rowModifier) {
                Text(
                    text = inboxTitle(uiState),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 错误提示内联展示（圆形表盘不适合弹出层），点击即重试
        if (uiState.error != null) {
            circularSlot(index++, listState, key = "error") { rowModifier ->
                ErrorBanner(
                    message = uiState.error,
                    modifier = rowModifier,
                    onRetry = {
                        onDismissError()
                        onRetry()
                    },
                )
            }
        }

        // 空态
        if (uiState.accounts.isEmpty()) {
            circularSlot(index++, listState, key = "empty-no-account") { rowModifier ->
                Column(
                    modifier = rowModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        icon = WearIcons.Account,
                        title = "还没有添加邮箱账户",
                        hint = "添加后即可在手表上收发邮件",
                    )
                    PrimaryActionButton(
                        text = "去添加账户",
                        icon = WearIcons.Add,
                        onClick = onOpenAccounts,
                    )
                }
            }
        } else if (isEmptyInbox) {
            circularSlot(index++, listState, key = "empty-inbox") { rowModifier ->
                EmptyState(
                    icon = WearIcons.MailOutline,
                    title = "收件箱是空的",
                    hint = "下拉即可刷新邮件",
                    modifier = rowModifier,
                )
            }
        }

        uiState.mails.forEach { mail ->
            circularSlot(index++, listState, key = mail.id) { rowModifier ->
                MailRow(
                    mail = mail,
                    colorIndex = uiState.accounts.firstOrNull { it.id == mail.accountId }?.colorIndex
                        ?: 0,
                    modifier = rowModifier,
                    onOpen = { onOpenEmail(mail.id) },
                    onMarkRead = { read -> onMarkRead(mail.id, read) },
                    onSetFlagged = { flagged -> onSetFlagged(mail.id, flagged) },
                    onDelete = { onDelete(mail.id) },
                )
            }
        }

        // 状态尾项：电量 / 最近同步时间 / 设置入口。
        // 放在列表尾部随内容滚动，而不是固定在底部 —— 固定占位会吃掉 30~40dp 的
        // 信息流高度，而这三项属于「滚到底顺手看一眼」的低频信息。
        circularSlot(index, listState, key = "status-footer") { rowModifier ->
            StatusBar(
                syncing = uiState.syncing,
                lastSuccessAt = uiState.lastSuccessAt,
                onOpenSettings = onOpenSettings,
                modifier = rowModifier,
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

/** 列表标题：当前筛选名 + 未读总数（未读为 0 时只显示筛选名） */
private fun inboxTitle(uiState: InboxUiState): String {
    val scope = uiState.accounts
        .firstOrNull { it.id == uiState.selectedAccountId }
        ?.let { InboxRowFormatter.accountFilterLabel(it) }
        ?: "全部"
    val unread = uiState.unreadCounts.values.sum()
    val badge = InboxRowFormatter.unreadBadgeText(unread)
    return if (badge.isEmpty()) "收件箱 · $scope" else "收件箱 · $scope ($badge)"
}

/**
 * 账户筛选行：横向滚动，第一项「全部」，其后每个账户一项。
 *
 * 选中态用 [Button]（高强调、填充主色背景），未选中用 [TextButton]（低强调），
 * 两者默认高度都不小于 48dp，满足触控热区要求。
 */
@Composable
private fun AccountFilterRow(
    accounts: List<Account>,
    selectedAccountId: Long?,
    unreadCounts: Map<Long, Int>,
    onSelect: (Long?) -> Unit,
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selectedAccountId == null,
            label = "全部",
            icon = {
                Icon(
                    imageVector = WearIcons.List,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            onClick = { onSelect(null) },
        )

        accounts.forEach { account ->
            val badge = InboxRowFormatter.unreadBadgeText(unreadCounts[account.id] ?: 0)
            FilterChip(
                selected = selectedAccountId == account.id,
                label = InboxRowFormatter.accountFilterLabel(account),
                icon = {
                    AccountInitialBadge(
                        label = account.initial,
                        colorIndex = account.colorIndex,
                        size = 20.dp,
                    )
                },
                trailing = badge.ifEmpty { null },
                onClick = { onSelect(account.id) },
            )
        }
    }
}

/**
 * 单个筛选项。
 *
 * 使用**迷你胶囊**（视觉高度 24dp = 常规胶囊的一半）：筛选条横跨圆屏上部，
 * 高度直接决定信息流的上边界；迷你化后同样能一行放下「全部 + 多个账户」，
 * 且 24dp = 48px 仍满足最小触控热区。
 *
 * 注意 `trailing`（未读数）需要显式着色 —— 迷你件不像 `CompactButton` 那样
 * 通过 contentColor 自动传递给子内容。
 */
@Composable
private fun FilterChip(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val trailingContent: (@Composable () -> Unit)? = if (trailing != null) {
        {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    } else {
        null
    }

    MiniOptionButton(
        text = label,
        selected = selected,
        onClick = onClick,
        leading = icon,
        trailing = trailingContent,
    )
}

/**
 * 状态信息行（**列表尾项**，不是固定底栏）。
 *
 * 内容：设置入口 + 同步状态 + 电量。
 *
 * 同步状态文案：
 * - 同步中 → 「同步中…」
 * - 否则 → [TimeFormat.relativeTime]（从未同步时显示「从未」）
 *
 * 为什么放在列表尾部：作为固定底栏时它要占 30~40dp 的纵向空间，
 * 而圆形表盘中间的信息流本来就窄；改为尾项后信息不丢（滚到底即可看到），
 * 却不占用固定高度。设置入口的小按钮视觉 32dp，触控热区 64px ≈ 24dp，
 * 满足需求「热区 ≥ 48px」。
 */
@Composable
private fun StatusBar(
    syncing: Boolean,
    lastSuccessAt: Long,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val battery = rememberBatteryPercent()
    val syncText = if (syncing) "同步中…" else TimeFormat.relativeTime(lastSuccessAt)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = WearIcons.Settings,
                contentDescription = "设置",
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = syncText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (battery != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$battery%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 邮件列表项。
 *
 * 需求约束与实现对应：
 * - 行高 >= 48px：卡片自身默认最小高度已 >= 48dp，这里再加
 *   `heightIn(min = 48.dp)` 双保险，保证圆形表盘上依然有足够触控热区；
 * - 未读：主题加粗 + [UnreadDot]；已读：整行用 `onSurfaceVariant` 弱化；
 * - 长按：弹出操作菜单（标记已读/未读、标星/取消星标、删除需二次确认）。
 */
@Composable
private fun MailRow(
    mail: EmailMeta,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onSetFlagged: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = rememberHaptics()
    var menuVisible by remember(mail.id) { mutableStateOf(false) }
    var confirmDelete by remember(mail.id) { mutableStateOf(false) }

    // 用 rememberUpdatedState 保证长按回调始终读到最新的选中状态与最新的入参 lambda
    val latestRead by rememberUpdatedState(mail.isRead)
    val latestFlagged by rememberUpdatedState(mail.isFlagged)
    val latestMarkRead by rememberUpdatedState(onMarkRead)
    val latestSetFlagged by rememberUpdatedState(onSetFlagged)

    val sender = remember(mail.from) { InboxRowFormatter.senderLabel(mail.from) }
    val subject = remember(mail.subject) { InboxRowFormatter.subjectLine(mail.subject) }
    val time = remember(mail.dateMillis) { TimeFormat.smartTime(mail.dateMillis) }
    val accent = if (mail.isRead) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = {
            haptics.tick()
            onOpen()
        },
        onLongClick = {
            haptics.longPress()
            menuVisible = true
        },
        onLongClickLabel = "邮件操作",
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountDot(colorIndex = colorIndex)
            Spacer(modifier = Modifier.width(5.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 第一行：发件人 + 时间
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sender,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent,
                        fontWeight = if (mail.isRead) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 第二行：主题（单行截断）
                Text(
                    text = subject,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mail.isRead) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (mail.isRead) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!mail.isRead) {
                Spacer(modifier = Modifier.width(5.dp))
                UnreadDot()
            }
        }
    }

    // ---------------- 长按操作菜单 ----------------
    MailActionDialog(
        visible = menuVisible,
        mail = mail,
        onDismiss = { menuVisible = false },
        onToggleRead = {
            menuVisible = false
            haptics.tick()
            latestMarkRead(!latestRead)
        },
        onToggleFlagged = {
            menuVisible = false
            haptics.tick()
            latestSetFlagged(!latestFlagged)
        },
        onRequestDelete = {
            menuVisible = false
            confirmDelete = true
        },
    )

    // ---------------- 删除二次确认 ----------------
    ConfirmDialog(
        visible = confirmDelete,
        title = "删除这封邮件？",
        message = "将同时从服务器删除，无法撤销。",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            confirmDelete = false
            haptics.tick()
            onDelete()
        },
        onDismiss = { confirmDelete = false },
    )
}

/**
 * 长按操作菜单。
 *
 * Wear 的 [androidx.wear.compose.material3.AlertDialog] 只有 confirm / dismiss 两个按钮槽，
 * 因此把「标记已读/未读」放在 confirm 槽，其余动作放在 text 槽内纵向排列（每个都 >= 48dp）。
 */
@Composable
private fun MailActionDialog(
    visible: Boolean,
    mail: EmailMeta,
    onDismiss: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleFlagged: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "邮件操作",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ActionRow(
                    icon = if (mail.isFlagged) WearIcons.Check else WearIcons.Star,
                    label = if (mail.isFlagged) "取消星标" else "标星",
                    onClick = onToggleFlagged,
                )
                ActionRow(
                    icon = WearIcons.Delete,
                    label = "删除",
                    onClick = onRequestDelete,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onToggleRead) {
                Text(text = if (mail.isRead) "标记未读" else "标记已读", maxLines = 1)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", maxLines = 1)
            }
        },
    )
}

/** 菜单内的一行动作（图标 + 文案，高度 >= 48dp 以满足触控热区） */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        colors = TextButtonDefaults.filledVariantTextButtonColors(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 圆形表盘的「纵向预算」（466px 表盘，密度 ≈2.0 → 高 233dp）。
 *
 * 安全圆半径 210px = 105dp，圆心在 116.5dp；顶部要给时间弧线 + 筛选器，
 * 底部要给主操作按钮，中间剩下的才是信息流。
 *
 * 演进过程（每一轮都来自"信息流太小"的反馈）：
 * 1. 早期顶部 88dp + 底部 136dp → 干净带 `233 − 88 − 136 = 9dp`（不到一行邮件）；
 * 2. 状态信息移到列表尾项 + 改用紧凑胶囊 → `233 − 84 − 84 = 65dp`（约 1.5 行）；
 * 3. **按钮减半**（筛选胶囊与主按钮 48dp → 24dp 迷你件）→ `233 − 56 − 54 = 123dp`
 *    （约 3 行），比第 2 轮再提升约 89%。
 *
 * 24dp 在本机密度下正好 48px，恰好等于需求要求的最小触控热区 —— 这是"不能再小"的下限。
 */
/** 筛选器距表盘顶部的距离：避开 CircularScreen 的 TimeText 弧线文字 */
private val FILTER_TOP_PADDING = 26.dp

/** 顶部渐隐遮罩高度（筛选器上边距 26dp + 迷你筛选胶囊 24dp + 渐隐余量） */
private val TOP_SCRIM_HEIGHT = 56.dp

/** 底部渐隐遮罩高度，同时作为列表底部内边距（迷你主按钮 24dp + 下边距 22dp + 渐隐余量） */
private val BOTTOM_SCRIM_HEIGHT = 54.dp

/**
 * 底部主按钮距表盘底部的距离。
 *
 * 约束来自圆弧：按钮底边位于 y = 233 − 22 = 211dp（422px），该高度可用弦长
 * `2·√(105² − (211 − 116.5)²) ≈ 91dp`，而迷你胶囊「新建邮件」
 * （图标 12dp + 3dp + 文字约 48dp + 左右内边距 20dp ≈ 83dp）能完整放下并留 4dp 余量；
 * 再往下移圆弧就会切掉胶囊两端。
 */
private val BOTTOM_BAR_PADDING = 22.dp
