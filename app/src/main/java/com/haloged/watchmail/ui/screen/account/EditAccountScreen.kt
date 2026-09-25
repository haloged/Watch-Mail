package com.haloged.watchmail.ui.screen.account

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.SwitchDefaults
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.local.entity.EncryptionType
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil

/**
 * 编辑账户页面
 *
 * 可修改：别名、密码、服务器主机/端口、加密方式、通知开关、启用状态
 * 保存时等待异步结果，成功才返回
 */
@Composable
fun EditAccountScreen(
    accountId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accounts by viewModel.accounts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val view = LocalView.current

    // 查找当前账户
    val account = remember(accounts, accountId) {
        accounts.find { it.id == accountId }
    }

    // 表单状态 - 从账户初始化
    var alias by remember(account) { mutableStateOf(account?.alias ?: "") }
    var password by remember(account) { mutableStateOf("") } // 留空表示不修改
    var imapHost by remember(account) { mutableStateOf(account?.imapHost ?: "") }
    var imapPort by remember(account) { mutableStateOf(account?.imapPort?.toString() ?: "993") }
    var imapEncryption by remember(account) { mutableStateOf(account?.imapEncryption ?: EncryptionType.SSL) }
    var smtpHost by remember(account) { mutableStateOf(account?.smtpHost ?: "") }
    var smtpPort by remember(account) { mutableStateOf(account?.smtpPort?.toString() ?: "587") }
    var smtpEncryption by remember(account) { mutableStateOf(account?.smtpEncryption ?: EncryptionType.STARTTLS) }
    var notificationEnabled by remember(account) { mutableStateOf(account?.notificationEnabled ?: true) }
    var isEnabled by remember(account) { mutableStateOf(account?.isEnabled ?: true) }
    var localError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    if (account == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(WatchMailColors.Background),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "账户不存在", style = WatchMailTypography.Body)
        }
        return
    }

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
                onSave = {
                    HapticUtil.performLightFeedback(view)
                    localError = null
                    // 更新账户（密码留空则保留原密码）
                    viewModel.updateAccount(
                        account.copy(
                            alias = alias.ifBlank { account.email.substringBefore("@") },
                            imapHost = imapHost.trim(),
                            imapPort = imapPort.toIntOrNull() ?: 993,
                            imapEncryption = imapEncryption,
                            smtpHost = smtpHost.trim(),
                            smtpPort = smtpPort.toIntOrNull() ?: 587,
                            smtpEncryption = smtpEncryption,
                            notificationEnabled = notificationEnabled,
                            isEnabled = isEnabled
                        )
                    ) { ok ->
                        if (ok) {
                            // 密码非空则一并更新（加密存储）
                            if (password.isNotBlank()) {
                                viewModel.updateAccountPassword(account.id, password)
                            }
                            HapticUtil.performConfirmFeedback(view)
                            onSuccess()
                        } else {
                            HapticUtil.performRejectFeedback(view)
                            localError = "保存失败"
                        }
                    }
                },
                isSaveEnabled = !isLoading
            )

            // 表单内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .crownScroll(scrollState)   // 表冠滚动 + 刻度震动
                    .padding(horizontal = 16.dp)
            ) {
                // 只读信息
                InfoField(label = "邮箱", value = account.email)
                Spacer(modifier = Modifier.height(8.dp))

                // 可编辑字段
                FormField(
                    label = "别名",
                    value = alias,
                    onValueChange = { alias = it },
                    placeholder = "如：工作"
                )
                Spacer(modifier = Modifier.height(6.dp))

                FormField(
                    label = "密码(留空不改)",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "输入新密码/授权码"
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "IMAP服务器",
                    style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.TextSecondary)
                )
                FormField(
                    label = "地址",
                    value = imapHost,
                    onValueChange = { imapHost = it },
                    placeholder = "imap.example.com"
                )
                FormField(
                    label = "端口",
                    value = imapPort,
                    onValueChange = { imapPort = it },
                    placeholder = "993"
                )
                EncryptionSelector(
                    label = "IMAP加密",
                    selected = imapEncryption,
                    onSelect = { imapEncryption = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "SMTP服务器",
                    style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.TextSecondary)
                )
                FormField(
                    label = "地址",
                    value = smtpHost,
                    onValueChange = { smtpHost = it },
                    placeholder = "smtp.example.com"
                )
                FormField(
                    label = "端口",
                    value = smtpPort,
                    onValueChange = { smtpPort = it },
                    placeholder = "587"
                )
                EncryptionSelector(
                    label = "SMTP加密",
                    selected = smtpEncryption,
                    onSelect = { smtpEncryption = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 通知开关
                ToggleRow(
                    title = "邮件通知",
                    checked = notificationEnabled,
                    onCheckedChange = { notificationEnabled = it }
                )

                // 启用开关
                ToggleRow(
                    title = "启用此账户",
                    checked = isEnabled,
                    onCheckedChange = { isEnabled = it }
                )

                // 测试连接
                var testing by remember { mutableStateOf(false) }
                var testResult by remember { mutableStateOf<String?>(null) }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(WatchMailColors.SurfaceVariant)
                        .clickable(enabled = !testing) {
                            testing = true
                            testResult = "测试中..."
                            val candidate = account.copy(
                                imapHost = imapHost.trim(),
                                imapPort = imapPort.toIntOrNull() ?: 993,
                                imapEncryption = imapEncryption,
                                smtpHost = smtpHost.trim(),
                                smtpPort = smtpPort.toIntOrNull() ?: 587,
                                smtpEncryption = smtpEncryption
                            )
                            viewModel.testAccountConnection(candidate) { ok ->
                                testing = false
                                testResult = if (ok) "连接成功 ✓" else "连接失败 ✗"
                            }
                        }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = testResult ?: "测试连接",
                        style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Primary)
                    )
                }

                localError?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Error)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        if (isLoading) {
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
private fun TopBar(onBack: () -> Unit, onSave: () -> Unit, isSaveEnabled: Boolean) {
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
            onClick = onBack
        )
        Text(text = "编辑账户", style = WatchMailTypography.Title)
        WatchIconButton(
            imageVector = Icons.Default.Check,
            contentDescription = "保存",
            onClick = onSave,
            tint = if (isSaveEnabled) WatchMailColors.Primary else WatchMailColors.TextTertiary,
            enabled = isSaveEnabled
        )
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextSecondary)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WatchMailColors.SurfaceVariant)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = value,
                style = WatchMailTypography.Body.copy(color = WatchMailColors.TextSecondary)
            )
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Column {
        Text(
            text = label,
            style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextSecondary)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WatchMailColors.Surface)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = WatchMailTypography.Body.copy(color = WatchMailColors.TextTertiary)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = WatchMailTypography.Body.copy(color = WatchMailColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

/**
 * 开关行
 */
@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = WatchMailTypography.ListItemTitle)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WatchMailColors.Primary,
                checkedTrackColor = WatchMailColors.Primary.copy(alpha = 0.5f)
            )
        )
    }
}

/**
 * 加密方式选择器
 */
@Composable
private fun EncryptionSelector(
    label: String,
    selected: EncryptionType,
    onSelect: (EncryptionType) -> Unit
) {
    Column {
        Text(
            text = label,
            style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextSecondary)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                EncryptionType.SSL to "SSL",
                EncryptionType.STARTTLS to "STARTTLS",
                EncryptionType.NONE to "无"
            ).forEach { (type, name) ->
                val isSelected = selected == type
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) WatchMailColors.Primary
                            else WatchMailColors.SurfaceVariant
                        )
                        .clickable { onSelect(type) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        style = WatchMailTypography.BodySmall.copy(
                            color = if (isSelected) WatchMailColors.TextPrimary
                            else WatchMailColors.TextSecondary
                        )
                    )
                }
            }
        }
    }
}
