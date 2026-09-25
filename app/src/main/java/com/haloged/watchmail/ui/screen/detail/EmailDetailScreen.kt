package com.haloged.watchmail.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.ChordInsets
import com.haloged.watchmail.ui.util.chordConstrained
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * 邮件详情页面
 *
 * 圆形表盘适配：
 *  - 顶部弧形区：返回 / 已读切换 / 删除
 *  - 中部：主题 + 发件人 + 收件人 + 纯文本正文（可上下滚动）
 *  - 底部弧形区：回复 / 已读切换 / 删除（固定操作栏）
 */
@Composable
fun EmailDetailScreen(
    emailId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedEmail by viewModel.selectedEmail.collectAsState()
    val emails by viewModel.emails.collectAsState()
    val emailBody by viewModel.emailBody.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // 优先使用 selectEmail 订阅到的实时数据，回退到列表查找
    val email = selectedEmail?.takeIf { it.id == emailId }
        ?: emails.find { it.id == emailId }

    // 加载邮件正文并标记为已读
    LaunchedEffect(emailId) {
        viewModel.selectEmail(emailId)
        viewModel.loadEmailBody(emailId)
        viewModel.markEmailAsRead(emailId, true)
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        if (email == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "邮件不存在",
                    style = WatchMailTypography.Body,
                    color = WatchMailColors.TextTertiary
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 顶部返回栏
                TopBar(onBack = onBack)

                // 邮件内容
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .crownScroll(scrollState)   // 表冠滚动 + 刻度震动
                        .padding(horizontal = 16.dp)
                ) {
                    // 主题
                    Text(
                        text = email.subject.ifBlank { "(无主题)" },
                        style = WatchMailTypography.TitleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 发件人信息
                    SenderInfo(
                        senderName = email.fromName?.takeIf { it.isNotBlank() } ?: email.fromAddress,
                        senderEmail = email.fromAddress,
                        time = email.receivedAt
                    )

                    // 收件人
                    if (email.toAddress.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "收件人: ${email.toAddress}",
                            style = WatchMailTypography.BodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 分隔线
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(WatchMailColors.SurfaceVariant)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 正文内容（纯文本；HTML 邮件已在解析阶段剥离标签）
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                indicatorColor = WatchMailColors.Primary
                            )
                        }
                    } else {
                        Text(
                            text = emailBody?.bodyText?.takeIf { it.isNotBlank() }
                                ?: email.preview.ifBlank { "(无正文)" },
                            style = WatchMailTypography.Body,
                            color = WatchMailColors.TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 底部固定操作栏
                BottomActionBar(
                    onReply = {
                        // 生成回复预填（收件人/主题 Re:/引用正文）再跳转撰写页
                        viewModel.prepareReply(email, emailBody?.bodyText)
                        onReply()
                    },
                    onDelete = { viewModel.deleteEmail(emailId) },
                    isRead = email.isRead,
                    onToggleRead = {
                        viewModel.markEmailAsRead(emailId, !email.isRead)
                    }
                )
            }
        }

        // 加载指示器
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    indicatorColor = WatchMailColors.Primary,
                    strokeWidth = 4.dp
                )
            }
        }
    }
}

/**
 * 顶部栏（仅返回，操作集中在底部固定栏，避免圆形顶部弧形区触控困难）
 */
@Composable
private fun TopBar(onBack: () -> Unit) {
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
            text = "邮件详情",
            style = WatchMailTypography.BodySmall
        )

        // 占位保持标题居中
        Spacer(modifier = Modifier.size(48.dp))
    }
}

/**
 * 发件人信息
 */
@Composable
private fun SenderInfo(
    senderName: String,
    senderEmail: String,
    time: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 发件人头像（圆形）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(WatchMailColors.Primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = senderName.firstOrNull()?.toString() ?: "@",
                style = WatchMailTypography.Title.copy(
                    color = WatchMailColors.Primary,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = senderName,
                style = WatchMailTypography.ListItemTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (senderEmail != senderName) {
                Text(
                    text = senderEmail,
                    style = WatchMailTypography.ListItemSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 时间
        Text(
            text = formatDetailTime(time),
            style = WatchMailTypography.EmailTime
        )
    }
}

/**
 * 格式化详情页时间
 */
private fun formatDetailTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * 底部固定操作栏
 * 回复 / 标记已读 / 删除，全部 ≥48dp 触控热区
 */
@Composable
private fun BottomActionBar(
    onReply: () -> Unit,
    onDelete: () -> Unit,
    isRead: Boolean,
    onToggleRead: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 抬高到底部弦宽足够的位置，否则按钮会被圆弧裁掉
            .padding(bottom = ChordInsets.BOTTOM_BAR_BOTTOM_PADDING_DP.dp)
            .height(36.dp)
            .chordConstrained()   // 宽度限制在可见弧内
            .background(WatchMailColors.Surface)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionButton(
            icon = Icons.AutoMirrored.Filled.Reply,
            label = "回复",
            onClick = onReply
        )

        ActionButton(
            icon = if (isRead) Icons.Default.Email else Icons.Default.MarkEmailRead,
            label = if (isRead) "未读" else "已读",
            onClick = onToggleRead
        )

        ActionButton(
            icon = Icons.Default.Delete,
            label = "删除",
            onClick = onDelete,
            tint = WatchMailColors.Error
        )
    }
}

/**
 * 操作按钮
 * 触控热区固定 48dp，图标 + 文字标签
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = WatchMailColors.TextPrimary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(36.dp) // 与图标按钮统一，省纵向空间
            .clip(RoundedCornerShape(8.dp))
            .background(WatchMailColors.SurfaceVariant)
            .padding(3.dp),
        verticalArrangement = Arrangement.Center
    ) {
        WatchIconButton(
            imageVector = icon,
            contentDescription = label,
            onClick = onClick,
            tint = tint
        )
    }
}
