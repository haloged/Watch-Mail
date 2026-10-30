package com.wm.wearmail.ui.screen.feedback

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.feedback.FeedbackLinks
import com.wm.wearmail.feedback.FeedbackQuota
import com.wm.wearmail.feedback.UploadDecision
import com.wm.wearmail.feedback.UploadQuotaPolicy
import com.wm.wearmail.feedback.VikaUploader
import com.wm.wearmail.pairing.QrCodeRenderer
import com.wm.wearmail.ui.screen.accounts.OAuthCodeLayout
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.SettingsActionRow
import com.wm.wearmail.ui.kit.ToastHost
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.rememberHaptics
import kotlinx.coroutines.launch

/** 反馈中心里的两类外部链接（标题用于弹窗标题） */
private enum class FeedbackLink(val title: String, val url: String) {
    GITHUB("GitHub 反馈", FeedbackLinks.GITHUB_NEW_ISSUE),
    FORM("表单反馈", FeedbackLinks.FORM_URL),
}

/**
 * 反馈中心（设置页入口）。
 *
 * 三种反馈方式：
 * 1. **前往 GitHub 反馈**：生成 Issues 新建页二维码（提示：可能遇到网络问题，但解决更快）；
 * 2. **填写表单反馈**：生成飞书表单二维码（提示：解决更慢，约两个版本内集中解决）；
 * 3. **上传日志**：把**当天**日志传到维格表反馈表，成功后在页面上显示**日志号**
 *    （用户把日志号填进 ①/② 的反馈里，开发者据此查日志）。
 *
 * 「在手表打开」：多数手表没有浏览器，失败时用浮层提示改用手机扫码。
 */
@Composable
fun FeedbackCenterScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var qrLink by remember { mutableStateOf<FeedbackLink?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadedLogId by remember { mutableStateOf<String?>(null) }
    // 风控：今天还可上传的次数（1 分钟内只能一次、每天 5 次）
    var remainingToday by remember { mutableStateOf(FeedbackQuota.remainingToday(container.app)) }

    CircularScreen {
        CircularSafeLazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
        ) {
            var index = 0

            // ---------------- 标题 + 返回 ----------------
            feedbackSlot(index++, listState) { modifier ->
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
                            contentDescription = "返回设置",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "反馈中心",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }

            // ---------------- 说明 ----------------
            feedbackSlot(index++, listState) { modifier ->
                Text(
                    text = "遇到问题？选一种方式反馈。上传日志后把日志号填进反馈里，定位会快很多。",
                    modifier = modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---------------- ① GitHub 反馈 ----------------
            feedbackSlot(index++, listState) { modifier ->
                SettingsActionRow(
                    onClick = {
                        haptics.tick()
                        qrLink = FeedbackLink.GITHUB
                    },
                    modifier = modifier.fillMaxWidth(),
                    icon = WearIcons.Send,
                    label = {
                        Text(
                            text = "前往 GitHub 反馈",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "可能会遇到网络问题，但解决更快",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    },
                )
            }

            // ---------------- ② 表单反馈 ----------------
            feedbackSlot(index++, listState) { modifier ->
                SettingsActionRow(
                    onClick = {
                        haptics.tick()
                        qrLink = FeedbackLink.FORM
                    },
                    modifier = modifier.fillMaxWidth(),
                    icon = WearIcons.Edit,
                    label = {
                        Text(
                            text = "填写表单反馈",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "解决更慢，大约两个版本内集中解决",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    },
                )
            }

            // ---------------- ③ 上传日志 ----------------
            feedbackSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    SettingsActionRow(
                        onClick = {
                            haptics.tick()
                            // 风控：1 分钟内只能上传一次、每天 5 次（按尝试计数，失败重试同样计入）
                            when (val decision = FeedbackQuota.evaluate(container.app)) {
                                is UploadDecision.TooSoon -> {
                                    val seconds = decision.remainMillis / 1000 + 1
                                    message = "上传太频繁：请 $seconds 秒后再试"
                                }

                                UploadDecision.DailyLimit -> {
                                    message = "今天的上传次数已用完（每天 ${UploadQuotaPolicy.MAX_UPLOADS_PER_DAY} 次）"
                                }

                                UploadDecision.Allowed -> {
                                    FeedbackQuota.recordAttempt(container.app)
                                    remainingToday = FeedbackQuota.remainingToday(container.app)
                                    uploading = true
                                    uploadedLogId = null
                                    scope.launch {
                                        val result = VikaUploader.uploadLog(container.app)
                                        uploading = false
                                        result.fold(
                                            onSuccess = { logId ->
                                                uploadedLogId = logId
                                                message = "日志已上传"
                                            },
                                            onFailure = { error ->
                                                message = error.message ?: "上传失败，请稍后重试"
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uploading && remainingToday > 0,
                        icon = WearIcons.Refresh,
                        label = {
                            Text(
                                text = if (uploading) "上传中…" else "上传日志",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = if (remainingToday > 0) {
                                    "只上传今天的日志 · 今天还可上传 $remainingToday 次"
                                } else {
                                    "今天的上传次数已用完（每天 ${UploadQuotaPolicy.MAX_UPLOADS_PER_DAY} 次）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        },
                    )
                    uploadedLogId?.let { logId ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "日志号",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        // 日志号单行完整显示：按可用宽度自适应字号（复用短码的同一套算法）
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val fontSize = OAuthCodeLayout.fontSizeSp(
                                availableWidthDp = maxWidth.value,
                                codeLength = logId.length,
                                letterSpacingSp = LOG_ID_LETTER_SPACING,
                                // 日志号信息密度高：允许比 OAuth 短码更小的下限（10sp），
                                // 保证 13 字符在圆屏最窄行也能完整显示
                                minFontSizeSp = 10f,
                            )
                            Text(
                                text = logId,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = fontSize.sp,
                                    letterSpacing = LOG_ID_LETTER_SPACING.sp,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "请把这个号填进反馈内容里",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    // ---------------- 二维码弹窗 ----------------
    qrLink?.let { link ->
        FeedbackQrDialog(
            link = link,
            onToast = { message = it },
            onDismiss = { qrLink = null },
        )
    }

    ToastHost(message = message, onDismiss = { message = null })
}

/** 外部链接二维码弹窗：二维码 + 「在手表打开」 + 关闭 */
@Composable
private fun FeedbackQrDialog(
    link: FeedbackLink,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(link) { QrCodeRenderer.render(link.url, QR_SIZE_PX) }

    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = link.title, maxLines = 1) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmap?.let { qr ->
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "反馈链接二维码",
                        modifier = Modifier.size(QR_DISPLAY_SIZE),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 多数手表没有浏览器：打开失败时提示改用手机扫码
                val opened = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                }.isSuccess
                if (!opened) onToast("手表上没有可打开链接的应用，请用手机扫码")
            }) {
                Text(text = "在手表打开")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        },
    )
}

/** 圆屏列表项封装（索引必须等于该项在列表中的真实位置） */
private fun LazyListScope.feedbackSlot(
    index: Int,
    listState: LazyListState,
    content: @Composable (Modifier) -> Unit,
) {
    item {
        CircularItem(index = index, state = listState) { rowModifier ->
            content(rowModifier)
        }
    }
}

/** 二维码内容区尺寸（弹窗内空间有限，过大反而挤压文字） */
private val QR_DISPLAY_SIZE = 120.dp

/** 二维码位图分辨率 */
private const val QR_SIZE_PX = 320

/** 日志号的字符间距（配合自适应字号，保证 18 字符单行完整显示） */
private const val LOG_ID_LETTER_SPACING = 0.5f
