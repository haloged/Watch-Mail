package com.wm.wearmail.ui.screen.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.ui.kit.CompactOptionButton
import com.wm.wearmail.ui.kit.CompactOptionGroup
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.Contact
import com.wm.wearmail.model.SendState
import com.wm.wearmail.ui.kit.AccountInitialBadge
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.ConfirmDialog
import com.wm.wearmail.ui.kit.EmptyState
import com.wm.wearmail.ui.kit.ErrorBanner
import com.wm.wearmail.ui.kit.LoadingIndicator
import com.wm.wearmail.ui.kit.PrimaryActionButton
import com.wm.wearmail.ui.kit.SectionHeader
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.WearTextField
import com.wm.wearmail.ui.kit.rememberHaptics
import com.wm.wearmail.ui.theme.SuccessColor
import kotlinx.coroutines.delay

/** 文本输入框最小高度：保证触控热区不小于 48dp */
private val TOUCH_TARGET_MIN_HEIGHT = 48.dp

/**
 * 撰写邮件页（覆盖层）。
 *
 * 手表端输入困难的应对策略：
 * - 收件人：优先展示常用联系人，点一下即填；也支持手动输入与语音输入；
 * - 主题：单行输入，回复场景自动补 `Re:`；
 * - 正文：多行输入 + 字数提示（超过 500 字提示将被引擎截断）；
 * - 发件账户：只有一个账户时无需选择，多账户时一排按钮切换。
 *
 * 发送状态机由 [ComposeMailViewModel.sendState] 驱动：
 * 发送中 → 转圈；成功 → ✓ + 1 秒后回调 [onSent]；失败 → ✗ + 重试按钮。
 */
@Composable
fun ComposeMailScreen(
    container: AppContainer,
    prefillTo: String?,
    prefillSubject: String?,
    draftId: Long?,
    onBack: () -> Unit,
    onSent: () -> Unit,
) {
    // 覆盖层的 ViewModel 会被 Activity 的 ViewModelStore 复用（没有独立的 BackStackEntry），
    // 因此用「会话 key」区分不同的撰写入口，并在进入本页时显式复位表单。
    val sessionKey = "compose-${draftId ?: 0}-${prefillTo.orEmpty()}-${prefillSubject.orEmpty()}"
    val vm: ComposeMailViewModel = viewModel(
        key = sessionKey,
        factory = ComposeMailViewModel.factory(
            container = container,
            prefillTo = prefillTo,
            prefillSubject = prefillSubject,
            draftId = draftId,
        ),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val sendState by vm.sendState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    // 每次进入本页都从干净状态开始
    LaunchedEffect(Unit) { vm.resetSession() }

    // 放弃撰写二次确认
    var confirmDiscard by remember { mutableStateOf(false) }

    /** 统一的「返回」处理：有内容先确认 */
    fun requestBack() {
        if (state.hasContent) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }

    // 发送结果 → 振动；成功 1 秒后返回
    LaunchedEffect(sendState) {
        when (sendState) {
            is SendState.Success -> {
                haptics.success()
                delay(SUCCESS_EXIT_DELAY_MS)
                onSent()
            }

            is SendState.Failure -> haptics.error()
            else -> Unit
        }
    }

    BackHandler(enabled = true) { requestBack() }

    CircularScreen {
        CircularSafeLazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
        ) {
            var index = 0

            // ---------------- 顶部：返回 + 标题 ----------------
            composeSlot(index++, listState) { modifier ->
                Row(
                    modifier = modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        haptics.tick()
                        requestBack()
                    }) {
                        Icon(
                            imageVector = WearIcons.Back,
                            contentDescription = "返回",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (state.fromDraft) "编辑草稿" else "写邮件",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.loading) {
                composeSlot(index++, listState) { modifier ->
                    Row(
                        modifier = modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            if (!state.loading && state.accounts.isEmpty()) {
                composeSlot(index++, listState) { modifier ->
                    EmptyState(
                        icon = WearIcons.Account,
                        title = "还没有邮箱账户",
                        hint = "请先在账户页添加邮箱账户，再回来写信",
                        modifier = modifier,
                    )
                }
                composeSlot(index, listState) { modifier ->
                    PrimaryActionButton(
                        text = "返回",
                        onClick = {
                            haptics.tick()
                            onBack()
                        },
                        modifier = modifier.fillMaxWidth(),
                        icon = WearIcons.Back,
                    )
                }
                return@CircularSafeLazyColumn
            }

            // ---------------- 发件账户 ----------------
            if (state.accounts.size > 1) {
                composeSlot(index++, listState) { modifier ->
                    SectionHeader(text = "发件账户", modifier = modifier)
                }
                // 发件账户：整组放在同一个列表项里自适应换行（紧凑胶囊 + 账户首字母徽标）。
                // 原实现是「一个账户一条整行 52dp 大按钮」，账户多时会占满整屏。
                composeSlot(index++, listState, key = "from-group") { modifier ->
                    CompactOptionGroup(modifier = modifier) {
                        state.accounts.forEach { account ->
                            CompactOptionButton(
                                text = account.displayLabel,
                                selected = state.selectedAccountId == account.id,
                                onClick = {
                                    haptics.tick()
                                    vm.selectAccount(account.id)
                                },
                                leading = {
                                    AccountInitialBadge(
                                        label = account.displayLabel,
                                        colorIndex = account.colorIndex,
                                        size = 16.dp,
                                    )
                                },
                            )
                        }
                    }
                }
            } else {
                state.selectedAccount?.let { account ->
                    composeSlot(index++, listState) { modifier ->
                        Text(
                            text = "发件账户：${account.email}",
                            modifier = modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ---------------- 收件人 ----------------
            composeSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    SectionHeader(text = "收件人")
                    WearTextField(
                        modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                        value = state.to,
                        onValueChange = vm::onToChange,
                        placeholder = "name@example.com",
                        singleLine = true,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    )
                }
            }

            // ---------------- 常用联系人（横向滚动，省纵向空间） ----------------
            if (state.contacts.isNotEmpty()) {
                composeSlot(index++, listState) { modifier ->
                    Column(modifier = modifier.fillMaxWidth()) {
                        SectionHeader(text = "常用联系人")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.contacts.forEach { contact ->
                                ContactChip(
                                    contact = contact,
                                    onClick = {
                                        haptics.tick()
                                        vm.useContact(contact)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 主题 ----------------
            composeSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    SectionHeader(text = "主题")
                    WearTextField(
                        modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                        value = state.subject,
                        onValueChange = vm::onSubjectChange,
                        placeholder = "邮件主题",
                        singleLine = true,
                        imeAction = ImeAction.Next,
                    )
                }
            }

            // ---------------- 正文 ----------------
            composeSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    SectionHeader(text = "正文")
                    WearTextField(
                        modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                        value = state.body,
                        onValueChange = vm::onBodyChange,
                        placeholder = "输入正文，可使用语音输入",
                        singleLine = false,
                        maxLines = BODY_MAX_LINES,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (state.bodyTooLong) {
                            "${state.bodyLength}/${ComposeMailUiState.MAX_BODY_CHARS} · 超出部分发送时会被截断"
                        } else {
                            "${state.bodyLength}/${ComposeMailUiState.MAX_BODY_CHARS}"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.bodyTooLong) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ---------------- 发送状态 / 发送按钮 ----------------
            composeSlot(index++, listState) { modifier ->
                SendArea(
                    state = sendState,
                    canSend = state.canSend,
                    modifier = modifier,
                    onSend = {
                        haptics.tick()
                        vm.send()
                    },
                    onRetry = {
                        haptics.tick()
                        vm.retry()
                    },
                )
            }

            // ---------------- 输入错误提示 ----------------
            state.error?.let { errorText ->
                composeSlot(index, listState) { modifier ->
                    ErrorBanner(message = errorText, modifier = modifier)
                }
            }
        }
    }

    ConfirmDialog(
        visible = confirmDiscard,
        title = "放弃这封邮件？",
        message = "已输入的内容不会保存（发送失败时才会自动存为草稿）。",
        confirmText = "放弃",
        dismissText = "继续写",
        onConfirm = {
            confirmDiscard = false
            onBack()
        },
        onDismiss = { confirmDiscard = false },
    )
}

/** 正文输入框显示行数（超出后内部滚动） */
private const val BODY_MAX_LINES = 6

/** 发送成功后停留展示 ✓ 的时长（毫秒） */
private const val SUCCESS_EXIT_DELAY_MS = 1_000L

/**
 * 发送区域：随 [SendState] 切换四种呈现。
 */
@Composable
private fun SendArea(
    state: SendState,
    canSend: Boolean,
    modifier: Modifier = Modifier,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is SendState.Idle -> {
                PrimaryActionButton(
                    text = "发送",
                    onClick = onSend,
                    modifier = Modifier.fillMaxWidth(),
                    icon = WearIcons.Send,
                    enabled = canSend,
                )
                if (!canSend) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "请填写正确的收件人邮箱",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }

            is SendState.Sending -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(size = 20.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "发送中…", style = MaterialTheme.typography.labelMedium)
                }
            }

            is SendState.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = WearIcons.Check,
                        contentDescription = null,
                        tint = SuccessColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已发送",
                        style = MaterialTheme.typography.labelMedium,
                        color = SuccessColor,
                    )
                }
            }

            is SendState.Failure -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = WearIcons.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                PrimaryActionButton(
                    text = "重试发送",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    icon = WearIcons.Refresh,
                )
            }
        }
    }
}

/** 常用联系人快捷项：紧凑胶囊（内容宽度），与收件箱筛选条保持同一视觉语言 */
@Composable
private fun ContactChip(
    contact: Contact,
    onClick: () -> Unit,
) {
    CompactOptionButton(
        text = contact.display,
        selected = false,
        onClick = onClick,
        showSelectedCheck = false,
    )
}

/**
 * 圆形安全列表项注册器（[index] 必须等于该项在 LazyColumn 中的真实索引）。
 */
private fun LazyListScope.composeSlot(
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
