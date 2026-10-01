package com.wm.wearmail.ui.screen.accounts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.mail.oauth.OAuthTokenService
import com.wm.wearmail.ui.kit.CompactOptionGroupOf
import com.wm.wearmail.ui.kit.SecondaryActionButton
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.ProviderPreset
import com.wm.wearmail.model.ProviderPresets
import com.wm.wearmail.pairing.PairingState
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.ErrorBanner
import com.wm.wearmail.ui.kit.LoadingIndicator
import com.wm.wearmail.ui.kit.PrimaryActionButton
import com.wm.wearmail.ui.kit.SectionHeader
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.WearTextField
import com.wm.wearmail.ui.kit.rememberHaptics

/**
 * 文本输入框的最小高度。
 *
 * 需求要求所有触控热区不小于 48x48dp；[WearTextField] 默认按内容高度测量
 * （约 36dp），因此在调用处统一补一个高度下限，避免手表上点不准。
 */
private val TOUCH_TARGET_MIN_HEIGHT = 48.dp

/**
 * 授权二维码的显示边长。
 *
 * 二维码按 300px 生成（保证纠错冗余），显示时缩到 132dp ≈ 264px：
 * 手机在 20~30cm 距离可稳定识别，同时不会挤占圆屏两侧安全区。
 */
private val QR_DISPLAY_SIZE = 132.dp

/**
 * 添加账户页（覆盖层，三步式表单）。
 *
 * 为什么是三步：
 * 手表端打字极其痛苦，把「邮箱/密码」「服务器参数」「验证保存」拆开，
 * 每一步只呈现少量输入框，并提供三种「少打字」的路径：
 * 1. 服务商预设（Gmail/Outlook/QQ/163 一键回填）；
 * 2. 自动探测（依次尝试候选主机与端口）；
 * 3. 手机扫码配置（同 Wi-Fi 下用手机浏览器填写，手表端零输入）。
 *
 * 安全：
 * - 密码只存在于 ViewModel 的内存流中，保存成功后立即清空；
 * - 离开本页时通过 [DisposableEffect] 关闭扫码服务并释放端口；
 * - 全程不打印密码、不打印正文。
 */
@Composable
fun AddAccountScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val vm: AddAccountViewModel = viewModel(factory = AddAccountViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val password by vm.password.collectAsStateWithLifecycle()
    // OAuth2 授权状态单独收集：其中**不含令牌内容**（令牌只在 ViewModel 私有字段里）
    val oauthState by vm.oauthState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    var message by remember { mutableStateOf<String?>(null) }

    // 覆盖层的 ViewModel 会被 Activity 的 ViewModelStore 复用，进入本页时显式复位表单
    LaunchedEffect(Unit) { vm.resetSession() }

    // 离开页面必须释放本地配置服务的端口（onCleared 中还有一次兜底）
    DisposableEffect(Unit) {
        onDispose { vm.stopPairing() }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AddAccountEvent.Saved -> {
                    message = null
                    haptics.success()
                    onDone()
                }

                is AddAccountEvent.Message -> {
                    message = event.text
                    if (event.success) haptics.success() else haptics.error()
                }
            }
        }
    }

    CircularScreen {
        CircularSafeLazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
        ) {
            // 计数器：与 LazyColumn 真实 item 索引严格一致（CircularItem 依赖它算弦长）
            var index = 0

            // ---------------- 标题 + 步骤指示 ----------------
            formSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            haptics.tick()
                            onBack()
                        }) {
                            Icon(
                                imageVector = WearIcons.Back,
                                contentDescription = "返回账户列表",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "添加账户",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${state.step}/${AddAccountViewModel.STEP_COUNT} · ${stepTitle(state.step)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            when (state.step) {
                AddAccountViewModel.STEP_EMAIL -> {
                    // ---------------- 步骤 1：邮箱与密码 ----------------
                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "邮箱地址")
                            WearTextField(
                                modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                value = state.email,
                                onValueChange = vm::onEmailChange,
                                placeholder = "name@example.com",
                                singleLine = true,
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            )
                        }
                    }

                    // 服务商预设：整组放在同一个列表项里自适应换行（紧凑胶囊）。
                    // 原实现是「一个预设一条整行 52dp 大按钮」，5 个预设就吃掉大半屏。
                    val presets: List<ProviderPreset> = ProviderPresets.all
                    formSlot(index++, listState, key = "preset-group") { modifier ->
                        CompactOptionGroupOf(
                            labels = presets.map { it.label },
                            selectedIndex = presets.indexOfFirst { it.id == state.presetId },
                            onSelect = { position ->
                                haptics.tick()
                                vm.selectPreset(presets[position])
                            },
                            modifier = modifier,
                        )
                    }

                    // 认证方式：OAuth2 场景允许密码为空
                    formSlot(index++, listState) { modifier ->
                        SectionHeader(text = "登录方式（可选）", modifier = modifier)
                    }
                    AuthType.entries.let { types ->
                        formSlot(index++, listState, key = "auth-group") { modifier ->
                            CompactOptionGroupOf(
                                labels = types.map { it.label },
                                selectedIndex = types.indexOf(state.authType),
                                onSelect = { position ->
                                    haptics.tick()
                                    vm.onAuthTypeChange(types[position])
                                },
                                modifier = modifier,
                            )
                        }
                    }

                    // 登录凭据：OAuth2（Outlook）走设备码授权面板，其余走密码输入框
                    formSlot(index++, listState, key = "credential") { modifier ->
                        if (state.authType == AuthType.OAUTH2) {
                            OAuthAuthorizePanel(
                                state = oauthState,
                                onStart = {
                                    haptics.tick()
                                    vm.startMicrosoftAuth()
                                },
                                onCancel = {
                                    haptics.tick()
                                    vm.cancelMicrosoftAuth()
                                },
                                modifier = modifier,
                            )
                        } else {
                            Column(modifier = modifier.fillMaxWidth()) {
                                SectionHeader(text = "密码 / 授权码")
                                WearTextField(
                                    modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                    value = password,
                                    onValueChange = vm::onPasswordChange,
                                    // 服务商提示放在输入框内，用户不用来回翻页找说明
                                    placeholder = state.authHint.ifBlank { "请输入密码或授权码" },
                                    singleLine = true,
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next,
                                )
                            }
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "账户别名（可选）")
                            WearTextField(
                                modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                value = state.alias,
                                onValueChange = vm::onAliasChange,
                                placeholder = "例如：工作",
                                singleLine = true,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Text(
                            text = "提示：多数服务商需先在网页端开启 IMAP/SMTP 并生成授权码",
                            modifier = modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                AddAccountViewModel.STEP_SERVER -> {
                    // ---------------- 步骤 2：服务器配置 ----------------
                    formSlot(index++, listState) { modifier ->
                        Column(
                            modifier = modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // 次要操作：紧凑胶囊（不占用整行，避免与「保存账户」抢视觉重心）
                            SecondaryActionButton(
                                text = if (state.probing) "探测中…" else "自动探测服务器",
                                onClick = {
                                    haptics.tick()
                                    vm.autoProbe()
                                },
                                icon = WearIcons.Search,
                                enabled = !state.probing,
                            )
                            if (state.probing) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LoadingIndicator()
                            }
                        }
                    }

                    state.probeMessage?.let { probe ->
                        formSlot(index++, listState) { modifier ->
                            ResultText(text = probe, success = state.probeSuccess, modifier = modifier)
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "IMAP 收件服务器")
                            WearTextField(
                                modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                value = state.imapHost,
                                onValueChange = vm::onImapHostChange,
                                placeholder = "imap.example.com",
                                singleLine = true,
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "IMAP 端口")
                            WearTextField(
                                modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                value = state.imapPort,
                                onValueChange = vm::onImapPortChange,
                                placeholder = "993",
                                singleLine = true,
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        SectionHeader(text = "IMAP 加密方式", modifier = modifier)
                    }
                    MailSecurity.entries.let { securities ->
                        formSlot(index++, listState, key = "imap-security-group") { modifier ->
                            CompactOptionGroupOf(
                                labels = securities.map { it.label },
                                selectedIndex = securities.indexOf(state.imapSecurity),
                                onSelect = { position ->
                                    haptics.tick()
                                    vm.onImapSecurityChange(securities[position])
                                },
                                modifier = modifier,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "SMTP 发件服务器")
                            WearTextField(
                                modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                value = state.smtpHost,
                                onValueChange = vm::onSmtpHostChange,
                                placeholder = "smtp.example.com",
                                singleLine = true,
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "SMTP 端口")
                            WearTextField(
                                modifier = Modifier.heightIn(min = TOUCH_TARGET_MIN_HEIGHT),
                                value = state.smtpPort,
                                onValueChange = vm::onSmtpPortChange,
                                placeholder = "465",
                                singleLine = true,
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        SectionHeader(text = "SMTP 加密方式", modifier = modifier)
                    }
                    MailSecurity.entries.let { securities ->
                        formSlot(index++, listState, key = "smtp-security-group") { modifier ->
                            CompactOptionGroupOf(
                                labels = securities.map { it.label },
                                selectedIndex = securities.indexOf(state.smtpSecurity),
                                onSelect = { position ->
                                    haptics.tick()
                                    vm.onSmtpSecurityChange(securities[position])
                                },
                                modifier = modifier,
                            )
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Text(
                            // 用户名固定使用邮箱地址，避免「用户名与邮箱不一致」这类常见错误
                            text = "登录名固定使用邮箱地址：${state.email.ifBlank { "（未填写）" }}",
                            modifier = modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                else -> {
                    // ---------------- 步骤 3：验证并保存 ----------------
                    formSlot(index++, listState) { modifier ->
                        Column(modifier = modifier.fillMaxWidth()) {
                            SectionHeader(text = "配置摘要")
                            SummaryLine(label = "邮箱", value = state.email.ifBlank { "（未填写）" })
                            SummaryLine(
                                label = "IMAP",
                                value = "${state.imapHost}:${state.imapPort} ${state.imapSecurity.label}",
                            )
                            SummaryLine(
                                label = "SMTP",
                                value = "${state.smtpHost}:${state.smtpPort} ${state.smtpSecurity.label}",
                            )
                            SummaryLine(label = "别名", value = state.alias.ifBlank { "（未设置）" })
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        Column(
                            modifier = modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // 次要操作：验证连接用紧凑胶囊，「保存账户」才是本步主操作
                            SecondaryActionButton(
                                text = if (state.verifying) "验证中…" else "验证连接",
                                onClick = {
                                    haptics.tick()
                                    vm.verify()
                                },
                                icon = WearIcons.Check,
                                enabled = !state.verifying,
                            )
                            if (state.verifying) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LoadingIndicator()
                            }
                        }
                    }

                    state.verifyMessage?.let { verifyText ->
                        formSlot(index++, listState) { modifier ->
                            ResultText(text = verifyText, success = state.verifySuccess, modifier = modifier)
                        }
                    }

                    formSlot(index++, listState) { modifier ->
                        PrimaryActionButton(
                            text = if (state.saving) "保存中…" else "保存账户",
                            onClick = {
                                haptics.tick()
                                vm.save()
                            },
                            modifier = modifier.fillMaxWidth(),
                            icon = WearIcons.Done,
                            enabled = !state.saving,
                        )
                    }
                }
            }

            // ---------------- 错误提示 ----------------
            state.error?.let { errorText ->
                formSlot(index++, listState) { modifier ->
                    ErrorBanner(message = errorText, modifier = modifier)
                }
            }

            message?.let { infoText ->
                formSlot(index++, listState) { modifier ->
                    Text(
                        text = infoText,
                        modifier = modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ---------------- 底部导航 ----------------
            if (state.step > AddAccountViewModel.STEP_EMAIL) {
                formSlot(index++, listState) { modifier ->
                    TextButton(
                        onClick = {
                            haptics.tick()
                            vm.prevStep()
                        },
                        modifier = modifier.fillMaxWidth(),
                    ) {
                        // 注意：TextButton 的 content 是 BoxScope，多个子项会重叠，
                        // 因此这里只放一段文本（图标放在外层 Row 会破坏圆形安全区布局）
                        Text(text = "‹ 上一步")
                    }
                }
            }

            if (state.step < AddAccountViewModel.STEP_CONFIRM) {
                formSlot(index++, listState) { modifier ->
                    PrimaryActionButton(
                        text = "下一步",
                        onClick = {
                            haptics.tick()
                            vm.nextStep()
                        },
                        modifier = modifier.fillMaxWidth(),
                    )
                }
            }

            // 手机扫码配置：手表端零输入的兜底路径
            formSlot(index, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    // 手机扫码配置属次要入口：紧凑胶囊 + 说明文字
                    SecondaryActionButton(
                        text = "用手机扫码配置",
                        onClick = {
                            haptics.tick()
                            vm.startPairing()
                        },
                        icon = WearIcons.Phone,
                        enabled = !state.pairingVisible,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "手机与手表连接同一个 Wi-Fi 后扫码，在手机上填写邮箱信息",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ---------------- 扫码配置面板 ----------------
    // Wear M3 的 AlertDialog 内部使用 ScalingLazyColumn：单个 item 不得高于可视区，
    // 因此二维码、地址、计数等各自成为一个 item，而不是塞进 text 里的一个大 Column。
    if (state.pairingVisible) {
        val pairing = state.pairing
        AlertDialog(
            visible = true,
            onDismissRequest = {
                // 关闭即释放端口
                vm.stopPairing()
            },
            title = { Text(text = "手机扫码配置") },
            text = {
                Text(
                    text = "手机与手表连接同一个 Wi-Fi 后扫码，在手机上填写邮箱信息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            content = {
                item { PairingQr(pairing = pairing) }

                pairing.url?.let { url ->
                    item {
                        // 二维码不方便扫时，用户也可以手动在手机浏览器输入该地址
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (pairing.submittedCount > 0) {
                    item {
                        Text(
                            text = "已保存 ${pairing.submittedCount} 个账户",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }

                pairing.lastMessage?.let { last ->
                    item {
                        Text(
                            text = last,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.tick()
                    vm.stopPairing()
                }) {
                    Text(text = "关闭")
                }
            },
        )
    }
}

/** 当前步骤标题 */
private fun stepTitle(step: Int): String = when (step) {
    AddAccountViewModel.STEP_EMAIL -> "邮箱与密码"
    AddAccountViewModel.STEP_SERVER -> "服务器配置"
    else -> "验证并保存"
}

/**
 * 二维码区域（对话框中的单个 item）。
 *
 * 二维码位图可能为 null（未连 Wi-Fi、端口被占用、生成失败），
 * 因此按「错误 > 二维码 > 加载中」的优先级降级展示，绝不调用 `!!`。
 *
 * 尺寸固定为 [QR_SIZE]：AlertDialog 的单项高度有限（上下各留 10% 屏高 +
 * 底部按钮区），过大的二维码会被裁切，反而更不好扫。
 */
@Composable
private fun PairingQr(pairing: PairingState) {
    val bitmap = pairing.qrBitmap

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            pairing.error != null -> {
                Text(
                    text = pairing.error,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "手机扫码配置二维码",
                    modifier = Modifier.size(QR_SIZE),
                )
            }

            pairing.running -> LoadingIndicator()

            else -> {
                Text(
                    text = "正在启动配置服务…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 对话框内二维码的显示边长（约 208 物理像素，手机近距离可稳定识别） */
private val QR_SIZE = 104.dp

/** 结果提示（探测/验证）：成功用主色，失败用错误色 */
@Composable
private fun ResultText(
    text: String,
    success: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (success) WearIcons.Check else WearIcons.Warning,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 摘要行：左标签右值 */
@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Outlook / Office 365 的 OAuth2 授权面板（设备码流）。
 *
 * 为什么用手表端用设备码流而不是"跳浏览器登录"：手表没有可用的浏览器控件，
 * 而设备码流正是为**输入受限设备**设计的 —— 手表只显示短码与二维码，
 * 用户在手机浏览器完成登录，手表轮询取令牌（微软暂不支持在二维码里预填短码，
 * 因此二维码只承载授权页地址，短码仍需手输）。
 *
 * 面板**不接触任何令牌**：只展示短码、网址、二维码与进度文案。
 */
@Composable
private fun OAuthAuthorizePanel(
    state: OAuthAuthUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(text = "Microsoft 账号授权")

        Text(
            text = "Outlook / Office 365 已停用密码登录，需用 Microsoft 账号授权",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(6.dp))

        when {
            // 未配置客户端 ID：给出可执行指引（导航层不归本页管，因此只提示不跳转）
            !state.configured -> Text(
                text = OAuthTokenService.MSG_NO_CLIENT_ID,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // 已授权：显示有效期；「重新授权」用于换账号或授权被撤销后重来
            state.authorized -> {
                Text(
                    text = "已授权（有效期至 ${expireTimeText(state.authorizedUntilMillis)}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                SecondaryActionButton(text = "重新授权", onClick = onStart)
            }

            // 已拿到短码：展示短码 + 二维码 + 进度
            state.userCode != null -> {
                Text(
                    text = "在手机浏览器打开下面的网址，输入此代码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.userCode.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )

                state.qrBitmap?.let { qr ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Microsoft 授权二维码",
                        modifier = Modifier.size(QR_DISPLAY_SIZE),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.verificationUri.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.statusText?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.waiting) {
                            LoadingIndicator(size = 14.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                SecondaryActionButton(text = "取消", onClick = onCancel)
            }

            state.requesting -> {
                LoadingIndicator()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.statusText ?: "正在获取授权码…",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                )
            }

            // 初始态：一键获取授权
            else -> PrimaryActionButton(text = "获取授权", onClick = onStart)
        }

        state.error?.let { error ->
            Spacer(modifier = Modifier.height(6.dp))
            ErrorBanner(message = error)
        }
    }
}

/** 把过期时刻格式化成「HH:mm」（仅用于展示授权有效期，失败返回空串） */
private fun expireTimeText(millis: Long): String = runCatching {
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}.getOrDefault("")

/**
 * 圆形安全列表项注册器（[index] 必须等于该项在 LazyColumn 中的真实索引）。
 */
private fun LazyListScope.formSlot(
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
