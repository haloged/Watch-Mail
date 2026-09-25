package com.haloged.watchmail.ui.screen.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.ui.components.EmailListItem
import com.haloged.watchmail.ui.components.EmptyState
import com.haloged.watchmail.ui.components.FilterChip
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.ChordInsets
import com.haloged.watchmail.ui.util.chordConstrained
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil

/**
 * 统一收件箱页面（主页面）
 *
 * 圆形表盘适配：
 *  - 顶部弧形区：状态栏（刷新 / 标题 / 未读数）
 *  - 中部：账户筛选器 + 邮件列表（列表项按 Y 坐标动态弦宽）
 *  - 底部弧形区：操作按钮（账户 / 写邮件 / 设置 / 草稿）
 *  - 顶部下拉手势触发刷新
 */
@Composable
fun InboxScreen(
    viewModel: MainViewModel,
    onEmailClick: (Long) -> Unit,
    onComposeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAccountsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val emails by viewModel.emails.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val selectedAccountId by viewModel.selectedAccountId.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val view = LocalView.current

    val accountColorMap = remember(accounts) {
        accounts.associate { it.id to WatchMailColors.getColorFromLong(it.color) }
    }

    // 合并收件箱：按时间倒序，可选按账户筛选
    val filteredEmails = remember(emails, selectedAccountId) {
        if (selectedAccountId == null) emails
        else emails.filter { it.accountId == selectedAccountId }
    }

    val listState = rememberLazyListState()

    // 下拉刷新：列表在顶部时继续下拉触发同步
    val pullToRefreshConnection = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                // 列表处于顶部且向下拖拽 → 触发刷新
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0 &&
                    available.y > 300f
                ) {
                    viewModel.syncAllAccounts()
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部弧形区
            TopBar(
                unreadCount = unreadCount,
                isSyncing = isSyncing,
                onRefresh = {
                    HapticUtil.performLightFeedback(view)
                    viewModel.syncAllAccounts()
                }
            )

            // 账户筛选器（支持全部账户，横向可滚动）
            if (accounts.isNotEmpty()) {
                FilterBar(
                    accounts = accounts,
                    selectedAccountId = selectedAccountId,
                    onSelect = {
                        HapticUtil.performLightFeedback(view)
                        viewModel.selectAccount(it)
                    }
                )
            }

            // 错误提示
            syncError?.let { error ->
                ErrorBar(message = error, onDismiss = { viewModel.clearError() })
            }

            // 邮件列表
            if (filteredEmails.isEmpty()) {
                EmptyState(
                    message = if (accounts.isEmpty()) "请先添加邮箱账户" else "暂无邮件",
                    modifier = Modifier.weight(1f)
                )
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .crownScroll(listState)   // 表冠滚动 + 刻度震动
                            .nestedScroll(pullToRefreshConnection),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(
                            items = filteredEmails,
                            key = { it.id },
                            contentType = { "email" }   // 固定类型，利于复用回收
                        ) { email ->
                            val color = remember(email.accountId) {
                                accountColorMap[email.accountId] ?: WatchMailColors.Primary
                            }
                            EmailListItem(
                                email = email,
                                accountColor = color,
                                onClick = {
                                    HapticUtil.performLightFeedback(view)
                                    onEmailClick(email.id)
                                }
                            )
                        }
                    }
                }
            }

            // 底部弧形区操作按钮
            BottomBar(
                onAccounts = {
                    HapticUtil.performLightFeedback(view)
                    onAccountsClick()
                },
                onCompose = {
                    HapticUtil.performLightFeedback(view)
                    onComposeClick()
                },
                onSettings = {
                    HapticUtil.performLightFeedback(view)
                    onSettingsClick()
                }
            )
        }

        // sync overlay
        if (isSyncing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    indicatorColor = WatchMailColors.Primary,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
private fun TopBar(unreadCount: Int, isSyncing: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ChordInsets.TOP_BAR_TOP_PADDING_DP.dp) // 抬高，避开顶部窄弦区
            .height(36.dp)
            .chordConstrained()   // 宽度限制在可见弧内，保证按钮不被裁切
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：刷新
        WatchIconButton(
            imageVector = Icons.Default.Refresh,
            contentDescription = "刷新",
            onClick = onRefresh,
            tint = if (isSyncing) WatchMailColors.TextTertiary else WatchMailColors.Primary,
            enabled = !isSyncing
        )

        // 中间：标题（字号收窄以让出按钮空间）
        Text(text = "收件箱", style = WatchMailTypography.Title.copy(fontSize = 17.sp))

        // 右侧：未读数量
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (unreadCount > 0) {
                Text(
                    text = "${unreadCount}",
                    style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Primary)
                )
            }
        }
    }
}

/**
 * 账户筛选器
 * "全部" + 所有已绑定账户（横向滚动，不再限制为 3 个）
 */
@Composable
private fun FilterBar(
    accounts: List<AccountEntity>,
    selectedAccountId: Long?,
    onSelect: (Long?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .chordConstrained()   // 同样限制在可见弧内，否则两端 chip 被裁
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(label = "全部", isSelected = selectedAccountId == null, onClick = { onSelect(null) })
        // 支持全部账户（可横向溢出滑动查看）
        accounts.forEach { account ->
            FilterChip(
                label = account.alias,
                isSelected = selectedAccountId == account.id,
                color = WatchMailColors.getColorFromLong(account.color),
                onClick = { onSelect(account.id) }
            )
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                WatchMailColors.Error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Error),
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
            WatchIconButton(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                onClick = onDismiss,
                tint = WatchMailColors.Error
            )
        }
    }
}

/**
 * 底部弧形区操作按钮
 *
 * ⚠ 圆形裁剪约束：底栏中心 y≈400px 处的可见弦宽只有
 *   2×√(233²−167²) = 325px ≈ 162dp。
 *   3 个 48dp 按钮(144dp) + 间隙刚好放得下；4 个必然溢出被圆弧裁掉
 *   （实测：第 4 个按钮中心 x=385，而该高度可见范围只到 x=384）。
 *   因此底栏收敛为 3 个：账户 / 写邮件 / 设置；草稿箱入口放到设置页。
 */
@Composable
private fun BottomBar(
    onAccounts: () -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ChordInsets.BOTTOM_BAR_BOTTOM_PADDING_DP.dp)
            .height(36.dp)
            .chordConstrained()   // 宽度限制在可见弧内
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户管理（左滑手势的等价入口）
        WatchIconButton(
            imageVector = Icons.Default.Mail,
            contentDescription = "账户",
            onClick = onAccounts,
            tint = WatchMailColors.TextSecondary
        )

        // 撰写邮件（主操作，放大强调）
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(WatchMailColors.Primary)
                .clickable(onClick = onCompose),
            contentAlignment = Alignment.Center
        ) {
            androidx.wear.compose.material.Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "写邮件",
                tint = WatchMailColors.TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }

        // 设置
        WatchIconButton(
            imageVector = Icons.Default.Settings,
            contentDescription = "设置",
            onClick = onSettings,
            tint = WatchMailColors.TextSecondary
        )
    }
}
