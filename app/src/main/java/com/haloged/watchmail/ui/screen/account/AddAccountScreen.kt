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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCodeScanner
import com.haloged.watchmail.data.local.entity.EncryptionType
import com.haloged.watchmail.data.remote.AutoConfigResult
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil
import kotlinx.coroutines.delay

/**
 * 添加账户页面
 *
 * 关键交互：
 *  - 提供「手机扫码配置」入口（手表起本地 Web 服务，手机同 WiFi 扫码填表，免手表打字）
 *  - 自动探测配置带 800ms 防抖，避免每次按键都发起网络请求
 *  - 保存时等待异步写库结果，成功才返回上一页（失败留在本页提示）
 *  - 高级配置可修改 IMAP/SMTP 主机、端口与加密方式
 */
@Composable
fun AddAccountScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    onPairQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val view = LocalView.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var imapEncryption by remember { mutableStateOf(EncryptionType.SSL) }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("587") }
    var smtpEncryption by remember { mutableStateOf(EncryptionType.STARTTLS) }
    var autoConfigDetected by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    // 自动探测配置（防抖 800ms，避免输入过程中频繁触发网络探测）
    LaunchedEffect(email) {
        if (email.contains("@") && email.contains(".")) {
            delay(800) // 防抖
            viewModel.detectEmailConfig(email) { result ->
                when (result) {
                    is AutoConfigResult.Success -> {
                        imapHost = result.config.imapHost
                        imapPort = result.config.imapPort.toString()
                        imapEncryption = result.config.imapEncryption
                        smtpHost = result.config.smtpHost
                        smtpPort = result.config.smtpPort.toString()
                        smtpEncryption = result.config.smtpEncryption
                        autoConfigDetected = true
                    }
                    is AutoConfigResult.Error -> {
                        autoConfigDetected = false
                    }
                }
            }
        }
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
                    if (email.isNotBlank() && password.isNotBlank() &&
                        imapHost.isNotBlank() && smtpHost.isNotBlank()
                    ) {
                        HapticUtil.performLightFeedback(view)
                        localError = null
                        // 等待异步结果后再决定是否返回
                        viewModel.addAccount(
                            email = email.trim(),
                            password = password,
                            alias = alias.ifBlank { email.substringBefore("@") },
                            imapHost = imapHost.trim(),
                            imapPort = imapPort.toIntOrNull() ?: 993,
                            imapEncryption = imapEncryption,
                            smtpHost = smtpHost.trim(),
                            smtpPort = smtpPort.toIntOrNull() ?: 587,
                            smtpEncryption = smtpEncryption
                        ) { ok, message ->
                            if (ok) {
                                HapticUtil.performConfirmFeedback(view)
                                onSuccess()
                            } else {
                                HapticUtil.performRejectFeedback(view)
                                localError = message ?: "添加失败"
                            }
                        }
                    } else {
                        localError = "请填写邮箱、密码及服务器配置"
                    }
                },
                isSaveEnabled = email.isNotBlank() && password.isNotBlank() &&
                        imapHost.isNotBlank() && smtpHost.isNotBlank() && !isLoading
            )

            // 表单内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .crownScroll(scrollState)   // 表冠滚动 + 刻度震动
                    .padding(horizontal = 16.dp)
            ) {
                // 手机扫码配置入口（手表打字困难，推荐用手机填表）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(WatchMailColors.Primary.copy(alpha = 0.18f))
                        .clickable {
                            HapticUtil.performLightFeedback(view)
                            onPairQr()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = WatchMailColors.Primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "手机扫码配置",
                                style = WatchMailTypography.ListItemTitle.copy(fontSize = 15.sp)
                            )
                            Text(
                                text = "同一 WiFi，用手机填表更快",
                                style = WatchMailTypography.ListItemSubtitle.copy(fontSize = 14.sp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FormField(
                    label = "邮箱地址",
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "your@email.com"
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 根据邮箱类型显示密码提示
                val passwordHint = getPasswordHint(email)
                FormField(
                    label = "密码/授权码",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = passwordHint
                )

                // 显示详细提示
                if (email.contains("@") && email.contains(".")) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val hint = getDetailedHint(email)
                    if (hint.isNotEmpty()) {
                        Text(
                            text = hint,
                            style = WatchMailTypography.Caption.copy(color = WatchMailColors.Warning),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                FormField(
                    label = "别名(可选)",
                    value = alias,
                    onValueChange = { alias = it },
                    placeholder = "如：工作"
                )

                if (autoConfigDetected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(WatchMailColors.Success.copy(alpha = 0.1f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "已自动检测配置",
                            style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Success)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 高级配置切换
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp) // 触控热区
                        .clip(RoundedCornerShape(8.dp))
                        .background(WatchMailColors.Surface)
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (showAdvanced) "隐藏高级配置 ▲" else "显示高级配置 ▼",
                        style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Primary)
                    )
                }

                if (showAdvanced) {
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
                }

                // 错误提示（本地 + ViewModel）
                (localError ?: errorMessage)?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(WatchMailColors.Error.copy(alpha = 0.1f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = error,
                            style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Error),
                            maxLines = 4
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 加载指示器
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
        Text(text = "添加账户", style = WatchMailTypography.Title)
        WatchIconButton(
            imageVector = Icons.Default.Check,
            contentDescription = "保存",
            onClick = onSave,
            tint = if (isSaveEnabled) WatchMailColors.Primary else WatchMailColors.TextTertiary,
            enabled = isSaveEnabled
        )
    }
}

/**
 * 加密方式选择器（SSL / STARTTLS）
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
                        .heightIn(min = 48.dp) // 触控热区
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
                .heightIn(min = 48.dp) // 触控热区
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
 * 根据邮箱类型获取密码提示
 */
private fun getPasswordHint(email: String): String {
    val domain = email.substringAfter("@").lowercase()
    return when {
        domain.contains("gmail") -> "输入应用专用密码"
        domain.contains("outlook") || domain.contains("hotmail") -> "输入应用密码"
        domain.contains("qq") -> "输入授权码(非QQ密码)"
        domain.contains("163") || domain.contains("126") -> "输入授权码(非登录密码)"
        else -> "输入密码或应用专用密码"
    }
}

/**
 * 根据邮箱类型获取详细提示
 */
private fun getDetailedHint(email: String): String {
    val domain = email.substringAfter("@").lowercase()
    return when {
        domain.contains("gmail") ->
            "需开启IMAP并生成应用专用密码"
        domain.contains("outlook") || domain.contains("hotmail") ->
            "需在Microsoft账号安全设置中生成应用密码"
        domain.contains("qq") ->
            "需在QQ邮箱设置→账户中开启IMAP服务并获取授权码"
        domain.contains("163") || domain.contains("126") ->
            "需在邮箱设置→POP3/SMTP/IMAP中开启服务并获取授权码"
        else -> ""
    }
}
