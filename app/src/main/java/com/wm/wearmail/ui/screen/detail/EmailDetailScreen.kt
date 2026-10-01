package com.wm.wearmail.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.RotaryBus
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.ConfirmDialog
import com.wm.wearmail.ui.kit.EmptyState
import com.wm.wearmail.ui.kit.ErrorBanner
import com.wm.wearmail.ui.kit.LoadingIndicator
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.rememberHaptics
import com.wm.wearmail.util.TimeFormat

/**
 * 邮件详情页（覆盖层）。
 *
 * 结构：
 * ```
 *  顶部：返回按钮（IconButton，热区 >= 48dp）
 *  可滚动区（CircularSafeLazyColumn）：发件人 / 收件人 / 时间 / 主题 / 正文
 *  底部固定操作栏（不随正文滚动）：回复 · 删除 · 已读未读
 * ```
 *
 * 正文为协议层剥离后的**纯文本**，不做 HTML 渲染，保证手表端渲染开销最小。
 *
 * @param onReply 回复回调，参数为收件人地址与预填主题（`Re: 原主题`）
 */
@Composable
fun EmailDetailScreen(
    container: AppContainer,
    emailId: Long,
    onBack: () -> Unit,
    onReply: (to: String, subject: String) -> Unit,
    /** 表冠旋转事件：详情页正文同样支持表冠滚动（为 null 时仅支持手指滚动） */
    rotaryBus: RotaryBus? = null,
) {
    // 用 emailId 作为 ViewModel 的 key：详情页是覆盖层，会复用同一个 ViewModelStore，
    // 若沿用默认 key（类名），从「邮件 A」返回后再打开「邮件 B」会拿到 A 的实例，
    // 从而显示错误的邮件。加 key 后每封邮件各自持有独立状态。
    val vm: EmailDetailViewModel = viewModel(
        key = "email-detail-$emailId",
        factory = EmailDetailViewModel.factory(container, emailId),
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    // 进入页面自动标记已读（带轻振动反馈）
    LaunchedEffect(emailId) {
        haptics.tick()
        vm.markRead(true)
    }

    // 自动拉取正文（loadBody 内部幂等，已缓存时不会重复请求）
    LaunchedEffect(emailId) {
        vm.loadBody()
    }

    // 删除完成后自动返回上一页
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }

    val mail = uiState.mail

    CircularScreen(modifier = Modifier.fillMaxSize()) {
        when {
            // 已删除：只等 onBack 生效，不渲染空态以免闪一下「邮件已删除」
            uiState.deleted -> Unit

            // 首帧尚未读到邮件：短暂显示加载态，避免误报「已删除」
            mail == null && uiState.loadingMail -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingIndicator()
                }
            }

            // 邮件确实已不存在（例如在其他页面被删除）
            uiState.mailGone -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        icon = WearIcons.Delete,
                        title = "邮件已删除",
                        hint = "这封邮件已不在本地缓存中",
                    )
                }
            }

            mail != null -> {
                DetailMessageList(
                    uiState = uiState,
                    listState = listState,
                    onRetryBody = { vm.retryLoadBody() },
                    rotaryBus = rotaryBus,
                )

                // ---------------- 底部固定操作栏 ----------------
                // 外层加「透明 → 表盘底色」的竖向渐变遮罩：
                // 正文滚到操作栏下方时会被渐隐，避免按钮与正文叠字。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(ACTION_BAR_SCRIM_HEIGHT)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    DetailActionBar(
                        mail = mail,
                        modifier = Modifier.padding(bottom = ACTION_BAR_BOTTOM_PADDING),
                        onReply = {
                            haptics.tick()
                            val to = mail.from.address
                            val subject = "Re: " + mail.subjectOrPlaceholder
                            onReply(to, subject)
                        },
                        onToggleRead = {
                            haptics.tick()
                            vm.markRead(!mail.isRead)
                        },
                        onDelete = {
                            haptics.tick()
                            vm.delete()
                        },
                    )
                }
            }
        }

        // ---------------- 顶部返回按钮 ----------------
        IconButton(
            onClick = {
                haptics.tick()
                onBack()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = BACK_BUTTON_PADDING, top = BACK_BUTTON_PADDING)
                .size(48.dp),
        ) {
            Icon(
                imageVector = WearIcons.Back,
                contentDescription = "返回",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 详情可滚动区。
 *
 * 正文按字符数切块（每块 <= [BODY_CHUNK_CHARS] 字符）后作为多个列表项渲染：
 * `CircularItem` 会按列表项在屏幕上的实际 Y 区间收窄宽度，过高的单个项在圆形
 * 安全区内可用宽度会趋近 0，因此必须切块——切块后每个块的高度足够小，
 * 横向内边距才能被正确计算，长正文也能完整、不被裁切地滚动阅读。
 */
@Composable
private fun DetailMessageList(
    uiState: DetailUiState,
    listState: LazyListState,
    onRetryBody: () -> Unit,
    /** 表冠旋转事件：正文列表同样支持表冠滚动 */
    rotaryBus: RotaryBus? = null,
) {
    val mail = uiState.mail ?: return
    val bodyChunks = remember(uiState.body) { splitBody(uiState.body) }

    CircularSafeLazyColumn(
        state = listState,
        // 上下内边距：顶部让开返回按钮，底部必须不小于固定操作栏占用的高度
        // （48dp 按钮 + 42dp 底边距 = 90dp），否则正文最后几行会被操作栏盖住。
        contentPadding = PaddingValues(top = 64.dp, bottom = 96.dp),
        active = true,
        rotaryBus = rotaryBus,
    ) {
        // 计数器：与 LazyColumn 的真实 item 索引严格一致
        var index = 0

        circularSlot(index++, listState, key = "sender") { rowModifier ->
            Text(
                text = mail.from.display,
                modifier = rowModifier,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        circularSlot(index++, listState, key = "to") { rowModifier ->
            Text(
                text = "收件人：" + recipientsLabel(mail),
                modifier = rowModifier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        circularSlot(index++, listState, key = "time") { rowModifier ->
            Text(
                text = TimeFormat.fullTime(mail.dateMillis),
                modifier = rowModifier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        circularSlot(index++, listState, key = "subject") { rowModifier ->
            Column(modifier = rowModifier) {
                Text(
                    text = mail.subjectOrPlaceholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    // 主题最多 2 行
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (mail.hasAttachments) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "含附件",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (uiState.loadingBody && bodyChunks.isEmpty()) {
            circularSlot(index++, listState, key = "body-loading") { rowModifier ->
                Row(
                    modifier = rowModifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(size = 20.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "正在加载正文…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        if (uiState.error != null) {
            circularSlot(index++, listState, key = "body-error") { rowModifier ->
                ErrorBanner(
                    message = uiState.error,
                    modifier = rowModifier,
                    onRetry = onRetryBody,
                )
            }
        }

        if (bodyChunks.isEmpty() && !uiState.loadingBody && uiState.error == null) {
            circularSlot(index++, listState, key = "body-empty") { rowModifier ->
                Text(
                    text = "（正文为空）",
                    modifier = rowModifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        bodyChunks.forEachIndexed { chunkIndex, chunk ->
            circularSlot(index++, listState, key = "body-$chunkIndex") { rowModifier ->
                Text(
                    text = chunk,
                    modifier = rowModifier,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 正文最小字号 14sp（bodyMedium 默认即 14sp），行高放宽便于阅读
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    softWrap = true,
                )
            }
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

/**
 * 底部固定操作栏：回复 / 删除 / 标记已读未读。
 *
 * 三个按钮均为 [IconButton] 家族，热区 48dp；删除走二次确认。
 */
@Composable
private fun DetailActionBar(
    mail: EmailMeta,
    onReply: () -> Unit,
    onToggleRead: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember(mail.id) { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 回复：主操作，用填充样式提高强调
        FilledIconButton(
            onClick = onReply,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = WearIcons.Reply,
                contentDescription = "回复",
                modifier = Modifier.size(20.dp),
            )
        }

        IconButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = WearIcons.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }

        IconButton(
            onClick = onToggleRead,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (mail.isRead) WearIcons.MailOutline else WearIcons.Done,
                contentDescription = if (mail.isRead) "标记未读" else "标记已读",
                modifier = Modifier.size(20.dp),
            )
        }
    }

    ConfirmDialog(
        visible = confirmDelete,
        title = "删除这封邮件？",
        message = "将同时从服务器删除，无法撤销。",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            confirmDelete = false
            onDelete()
        },
        onDismiss = { confirmDelete = false },
    )
}

/** 收件人展示：取显示名（无则用地址），逗号分隔 */
private fun recipientsLabel(mail: EmailMeta): String {
    val recipients = mail.to.ifEmpty { mail.cc }
    if (recipients.isEmpty()) return "(无收件人)"
    return recipients.joinToString(", ") { it.display }
}

/**
 * 把纯文本正文按空行切段，并把过长的段落再按字符数切块。
 *
 * 切块而不是整段渲染的原因见 [DetailMessageList] 的注释：
 * 单个列表项过高时，`CircularItem` 的弦长计算会给出趋近 0 的可用宽度。
 * 这里再对超长段落做二次切分，保证每个列表项高度可控。
 */
private fun splitBody(body: String?): List<String> {
    val text = body?.takeIf { it.isNotBlank() } ?: return emptyList()
    val paragraphs = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n\\s*\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    return paragraphs.flatMap { paragraph ->
        if (paragraph.length <= BODY_CHUNK_CHARS) {
            listOf(paragraph)
        } else {
            // 按字符数硬切（正文为纯文本，不做词法分析以保持行为可预测）
            paragraph.chunked(BODY_CHUNK_CHARS)
        }
    }
}

/** 返回按钮距表盘左上边缘的距离（46dp ≈ 92px，落点在安全圆内） */
private val BACK_BUTTON_PADDING = 46.dp

/**
 * 操作栏距表盘底部的距离。
 *
 * 操作栏是「回复 / 删除 / 已读」三个 48dp 图标按钮，共宽 144dp。
 * 底边距 42dp 时按钮底边位于 y = 233 − 42 = 191dp（382px），
 * 该处可用弦长 `2·√(210² − 149²) ≈ 296px ≈ 148dp`，刚好容纳 144dp 而不碰圆弧。
 * 原值 54dp 会让操作栏明显浮在半空（离底 54dp），因此下调到几何允许的下限附近。
 */
private val ACTION_BAR_BOTTOM_PADDING = 42.dp

/** 操作栏渐变遮罩高度（操作栏 48dp + 下边距 42dp + 渐隐余量） */
private val ACTION_BAR_SCRIM_HEIGHT = 96.dp

/** 正文切块字符数：控制单个列表项高度，避免圆形安全区把长文本压扁 */
private const val BODY_CHUNK_CHARS = 220
