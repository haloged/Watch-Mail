package com.haloged.watchmail.ui.screen.account

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.SwitchDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.ui.components.EmptyState
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil

/**
 * 账户管理页面
 * 展示已绑定账户，支持添加/删除/编辑/每账户通知开关
 * 新增：手机扫码配对添加账户（手表起本地 Web 服务，手机同 WiFi 扫码配置）
 */
@Composable
fun AccountScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onPairQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accounts by viewModel.accounts.collectAsState()
    val view = LocalView.current
    val accountListState = rememberLazyListState()

    // 删除确认对话框状态
    var showDeleteDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<AccountEntity?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            TopBar(
                onBack = {
                    HapticUtil.performLightFeedback(view)
                    onBack()
                },
                onAdd = {
                    HapticUtil.performLightFeedback(view)
                    onAddAccount()
                }
            )

            // 手机扫码配对入口（常驻，最省事的添加方式）
            QrPairEntry(
                onClick = {
                    HapticUtil.performLightFeedback(view)
                    onPairQr()
                }
            )

            // 账户列表
            if (accounts.isEmpty()) {
                EmptyState(
                    message = "暂无邮箱账户\n可在上方扫码配置",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = accountListState,
                    modifier = Modifier
                        .weight(1f)
                        .crownScroll(accountListState),   // 表冠滚动 + 刻度震动
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items = accounts, key = { it.id }) { account ->
                        AccountItem(
                            account = account,
                            onEdit = {
                                HapticUtil.performLightFeedback(view)
                                onEditAccount(account.id)
                            },
                            onDelete = {
                                HapticUtil.performLongPressFeedback(view)
                                accountToDelete = account
                                showDeleteDialog = true
                            },
                            onToggleNotification = { enabled ->
                                HapticUtil.performLightFeedback(view)
                                viewModel.setAccountNotificationEnabled(account.id, enabled)
                            }
                        )
                    }
                }
            }
        }

        // 删除确认对话框
        if (showDeleteDialog && accountToDelete != null) {
            DeleteConfirmDialog(
                accountName = accountToDelete!!.alias,
                onConfirm = {
                    HapticUtil.performConfirmFeedback(view)
                    viewModel.deleteAccount(accountToDelete!!.id)
                    showDeleteDialog = false
                    accountToDelete = null
                },
                onDismiss = {
                    HapticUtil.performLightFeedback(view)
                    showDeleteDialog = false
                    accountToDelete = null
                }
            )
        }
    }
}

/**
 * 手机扫码配对入口
 * 点击后进入二维码页面：手表起本地 Web 服务，手机同 WiFi 扫码即可填写邮箱配置
 */
@Composable
private fun QrPairEntry(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 56.dp) // 触控热区 ≥48dp
            .clip(RoundedCornerShape(12.dp))
            .background(WatchMailColors.Primary.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 扫码图标
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(WatchMailColors.Primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = WatchMailColors.TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "手机扫码配置",
                style = WatchMailTypography.ListItemTitle.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "同一 WiFi · 手机填表更方便",
                style = WatchMailTypography.ListItemSubtitle.copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.Default.QrCode,
            contentDescription = null,
            tint = WatchMailColors.Primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 账户列表项
 * 含账户色标、别名、邮箱、通知开关、编辑/删除
 */
@Composable
private fun AccountItem(
    account: AccountEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleNotification: (Boolean) -> Unit
) {
    val accountColor = WatchMailColors.getColorFromLong(account.color)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp) // 触控热区 ≥48dp
            .clip(RoundedCornerShape(12.dp))
            .background(WatchMailColors.Surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户颜色标识
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accountColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.alias.firstOrNull()?.toString() ?: "@",
                style = WatchMailTypography.Title.copy(
                    color = accountColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 账户信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.alias,
                style = WatchMailTypography.ListItemTitle.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = account.email,
                style = WatchMailTypography.ListItemSubtitle.copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 每账户通知开关
        Switch(
            checked = account.notificationEnabled,
            onCheckedChange = onToggleNotification,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WatchMailColors.Primary,
                checkedTrackColor = WatchMailColors.Primary.copy(alpha = 0.5f)
            )
        )

        // 编辑按钮
        WatchIconButton(
            imageVector = Icons.Default.Edit,
            contentDescription = "编辑",
            onClick = onEdit,
            tint = WatchMailColors.Primary
        )

        // 删除按钮
        WatchIconButton(
            imageVector = Icons.Default.Delete,
            contentDescription = "删除",
            onClick = onDelete,
            tint = WatchMailColors.Error
        )
    }
}

/**
 * 删除确认对话框
 */
@Composable
private fun DeleteConfirmDialog(
    accountName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchMailColors.Background.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WatchMailColors.Surface)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "删除账户",
                style = WatchMailTypography.Title
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "确定删除 $accountName ?",
                style = WatchMailTypography.BodySmall,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WatchIconButton(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "取消",
                    onClick = onDismiss,
                    tint = WatchMailColors.TextSecondary
                )

                WatchIconButton(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "确认删除",
                    onClick = onConfirm,
                    tint = WatchMailColors.Error
                )
            }
        }
    }
}

/**
 * 顶部栏
 */
@Composable
private fun TopBar(
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        WatchIconButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = WatchMailColors.TextPrimary
        )

        Text(
            text = "邮箱账户",
            style = WatchMailTypography.Title
        )

        WatchIconButton(
            imageVector = Icons.Default.Add,
            contentDescription = "添加",
            onClick = onAdd,
            tint = WatchMailColors.Primary
        )
    }
}
