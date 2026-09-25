package com.haloged.watchmail.ui.screen.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.remote.smtp.SmtpSendResult
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.components.WatchTextButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel

/**
 * 撰写邮件页面
 *
 * 圆形表盘适配：
 *  - 顶部：取消 / 标题 / 发送
 *  - 中部：发件账户、收件人、主题、正文（正文限 500 字）
 *  - 支持"回复"预填、存草稿、发送失败自动转存草稿
 */
@Composable
fun ComposeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSendSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accounts by viewModel.accounts.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val sendResult by viewModel.sendResult.collectAsState()
    val prefill by viewModel.composePrefill.collectAsState()

    // 表单状态
    var selectedAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var toAddress by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    // 应用预填数据（回复 / 草稿续写）
    LaunchedEffect(prefill) {
        prefill?.let { p ->
            toAddress = p.toAddress
            subject = p.subject
            body = p.body.take(500) // 钳制在 500 字以内
            if (p.accountId != null) {
                selectedAccount = accounts.find { it.id == p.accountId }
            }
            viewModel.clearComposePrefill()
        }
    }

    // 初始化选中账户（优先预填账户，否则第一个）
    LaunchedEffect(accounts) {
        if (selectedAccount == null && accounts.isNotEmpty()) {
            selectedAccount = prefill?.accountId?.let { id -> accounts.find { it.id == id } }
                ?: accounts.first()
        }
    }

    // 发送结果处理
    LaunchedEffect(sendResult) {
        when (sendResult) {
            is SmtpSendResult.Success -> {
                onSendSuccess()
                viewModel.clearSendResult()
            }
            else -> {}
        }
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopBar(
                onBack = onBack,
                onSend = {
                    val account = selectedAccount
                    if (account != null && toAddress.isNotBlank()) {
                        viewModel.sendEmail(account.id, toAddress, subject, body)
                    }
                },
                onSaveDraft = {
                    val account = selectedAccount
                    if (account != null) {
                        viewModel.saveDraft(account.id, toAddress, subject, body)
                        onBack()
                    }
                },
                isSendEnabled = selectedAccount != null && toAddress.isNotBlank() && !isSending
            )

            // 表单内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .crownScroll(scrollState)   // 表冠滚动 + 刻度震动
                    .padding(horizontal = 16.dp)
            ) {
                // 发件账户选择
                AccountSelector(
                    accounts = accounts,
                    selectedAccount = selectedAccount,
                    onAccountSelected = { selectedAccount = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 收件人
                InputField(
                    label = "收件人",
                    value = toAddress,
                    onValueChange = { toAddress = it },
                    placeholder = "输入邮箱地址"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 主题
                InputField(
                    label = "主题",
                    value = subject,
                    onValueChange = { subject = it },
                    placeholder = "输入邮件主题"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 正文
                BodyInputField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = "输入邮件正文（最多500字）"
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // 发送状态显示
            if (isSending) {
                SendingIndicator()
            }

            // 发送失败提示
            if (sendResult is SmtpSendResult.Error) {
                ErrorMessage(
                    message = (sendResult as SmtpSendResult.Error).message,
                    onRetry = {
                        val account = selectedAccount
                        if (account != null && toAddress.isNotBlank()) {
                            viewModel.sendEmail(account.id, toAddress, subject, body)
                        }
                    },
                    onDismiss = { viewModel.clearSendResult() }
                )
            }
        }
    }
}

/**
 * 顶部栏：取消 / 标题 / 存草稿 / 发送
 */
@Composable
private fun TopBar(
    onBack: () -> Unit,
    onSend: () -> Unit,
    onSaveDraft: () -> Unit,
    isSendEnabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        WatchIconButton(
            imageVector = Icons.Default.Close,
            contentDescription = "取消",
            onClick = onBack,
            tint = WatchMailColors.TextPrimary
        )

        Text(
            text = "写邮件",
            style = WatchMailTypography.Title
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 存草稿（离线暂存）
            WatchIconButton(
                imageVector = Icons.Default.Save,
                contentDescription = "存草稿",
                onClick = onSaveDraft,
                tint = WatchMailColors.TextSecondary
            )

            // 发送
            WatchIconButton(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                onClick = onSend,
                tint = if (isSendEnabled) WatchMailColors.Primary else WatchMailColors.TextTertiary,
                enabled = isSendEnabled
            )
        }
    }
}

/**
 * 账户选择器
 */
@Composable
private fun AccountSelector(
    accounts: List<AccountEntity>,
    selectedAccount: AccountEntity?,
    onAccountSelected: (AccountEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "发件账户",
            style = WatchMailTypography.BodySmall,
            color = WatchMailColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 选中的账户显示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp) // 触控热区 ≥48dp
                .clip(RoundedCornerShape(8.dp))
                .background(WatchMailColors.Surface)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (selectedAccount != null) {
                val accountColor = WatchMailColors.getColorFromLong(selectedAccount.color)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(accountColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${selectedAccount.alias}",
                        style = WatchMailTypography.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "选择账户",
                    style = WatchMailTypography.Body,
                    color = WatchMailColors.TextTertiary
                )
            }
        }

        // 展开的账户列表
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WatchMailColors.Surface)
            ) {
                accounts.forEach { account ->
                    val accountColor = WatchMailColors.getColorFromLong(account.color)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp) // 触控热区 ≥48dp
                            .clickable {
                                onAccountSelected(account)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accountColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${account.alias}",
                                style = WatchMailTypography.Body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 输入字段（触控热区 ≥48dp）
 */
@Composable
private fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Column {
        Text(
            text = label,
            style = WatchMailTypography.BodySmall,
            color = WatchMailColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WatchMailColors.Surface)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = WatchMailTypography.Body,
                    color = WatchMailColors.TextTertiary
                )
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = WatchMailTypography.Body.copy(
                    color = WatchMailColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

/**
 * 正文输入字段（限 500 字）
 */
@Composable
private fun BodyInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Column {
        Text(
            text = "正文",
            style = WatchMailTypography.BodySmall,
            color = WatchMailColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WatchMailColors.Surface)
                .padding(12.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = WatchMailTypography.Body,
                    color = WatchMailColors.TextTertiary
                )
            }

            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 500) onValueChange(it) },
                textStyle = WatchMailTypography.Body.copy(
                    color = WatchMailColors.TextPrimary
                ),
                modifier = Modifier.fillMaxSize(),
                maxLines = 10
            )
        }

        // 字数统计
        Text(
            text = "${value.length}/500",
            style = WatchMailTypography.Caption,
            color = if (value.length > 450) WatchMailColors.Warning else WatchMailColors.TextTertiary,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

/**
 * 发送中指示器
 */
@Composable
private fun SendingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(WatchMailColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                indicatorColor = WatchMailColors.Primary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "发送中...",
                style = WatchMailTypography.Body,
                color = WatchMailColors.Primary
            )
        }
    }
}

/**
 * 错误消息（含重试按钮）
 * 发送失败时邮件已自动转存草稿，可稍后由后台补发
 */
@Composable
private fun ErrorMessage(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(WatchMailColors.Error.copy(alpha = 0.1f))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = WatchMailTypography.BodySmall.copy(
                    color = WatchMailColors.Error
                ),
                modifier = Modifier.weight(1f),
                maxLines = 2
            )

            Row {
                WatchTextButton(
                    text = "重试",
                    onClick = onRetry
                )
                WatchTextButton(
                    text = "关闭",
                    onClick = onDismiss
                )
            }
        }
    }
}
