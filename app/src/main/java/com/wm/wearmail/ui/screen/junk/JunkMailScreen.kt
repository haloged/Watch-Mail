package com.wm.wearmail.ui.screen.junk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.ui.screen.inbox.MailRow
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.EmptyState
import com.wm.wearmail.ui.kit.LoadingIndicator
import com.wm.wearmail.ui.kit.SecondaryActionButton
import com.wm.wearmail.ui.kit.SettingsActionRow
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.rememberHaptics

/**
 * 垃圾邮件箱：所有账户的垃圾邮件合并展示（入口在收件箱列表最底部）。
 *
 * 安全默认：
 * - 顶部固定显示**风险提示**（钓鱼/恶意内容）；
 * - 详情页对本页打开的邮件**默认不加载图片、不打开链接**
 *   （设置里「垃圾邮件加载图片与链接」开启后才按普通邮件规则处理）。
 */
@Composable
fun JunkMailScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMail: (Long) -> Unit,
) {
    val vm: JunkMailViewModel = viewModel(factory = JunkMailViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    CircularScreen {
        CircularSafeLazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
        ) {
            var index = 0

            // ---------------- 标题 + 返回 ----------------
            junkSlot(index++, listState) { modifier ->
                Row(
                    modifier = modifier.fillMaxWidth(),
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
                        text = "垃圾邮件箱",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }

            // ---------------- 风险提示（固定展示，不可关闭） ----------------
            junkSlot(index++, listState) { modifier ->
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(10.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "注意风险",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "垃圾邮件可能包含钓鱼或恶意内容。本页打开邮件时**默认不加载图片、不打开链接**" +
                                "（可在「设置 → 邮件正文」里开启）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ---------------- 内容 ----------------
            when {
                state.loading -> junkSlot(index++, listState) { modifier ->
                    Row(
                        modifier = modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator(size = 20.dp)
                    }
                }

                state.error != null -> junkSlot(index++, listState) { modifier ->
                    Text(
                        text = state.error.orEmpty(),
                        modifier = modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }

                state.mails.isEmpty() -> junkSlot(index++, listState) { modifier ->
                    EmptyState(
                        icon = WearIcons.Warning,
                        title = "没有垃圾邮件",
                        hint = "各账户的垃圾文件夹都是空的",
                        modifier = modifier,
                    )
                }

                else -> state.mails.forEach { mail ->
                    junkSlot(index++, listState, key = mail.id) { modifier ->
                        // 复用收件箱的邮件行：同样的布局、同样最前面的账户色点；
                        // menuEnabled = false —— 垃圾邮件只读浏览，避免误触标记/删除
                        MailRow(
                            mail = mail,
                            colorIndex = state.accounts.firstOrNull { it.id == mail.accountId }?.colorIndex ?: 0,
                            modifier = modifier,
                            onOpen = { onOpenMail(mail.id) },
                            onMarkRead = {},
                            onSetFlagged = {},
                            onDelete = {},
                            menuEnabled = false,
                        )
                    }
                }
            }

            // ---------------- 重新拉取 ----------------
            junkSlot(index, listState) { modifier ->
                SecondaryActionButton(
                    text = if (state.loading) "拉取中…" else "重新拉取",
                    onClick = {
                        haptics.tick()
                        vm.refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.loading,
                    icon = WearIcons.Refresh,
                )
            }
        }
    }
}

/** 圆屏列表项封装（索引必须等于真实位置） */
private fun LazyListScope.junkSlot(
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
