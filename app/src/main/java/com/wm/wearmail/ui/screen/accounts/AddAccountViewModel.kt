package com.wm.wearmail.ui.screen.accounts

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.MailError
import com.wm.wearmail.mail.oauth.DeviceCodeStatus
import com.wm.wearmail.mail.oauth.OAuthTokenService
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.OAuthTokens
import com.wm.wearmail.model.ProviderPreset
import com.wm.wearmail.model.ProviderPresets
import com.wm.wearmail.model.ServerConfig
import com.wm.wearmail.pairing.PairingState
import com.wm.wearmail.pairing.QrCodeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「添加账户」三步表单状态。
 *
 * **安全约定：密码不放进本状态类**。原因有二：
 * 1. data class 的 `toString()` 会打印全部字段，任何一处 `Logs.d(state)` 都会泄密；
 * 2. Compose 快照状态的读写更频繁，越少触碰敏感数据越安全。
 * 因此密码单独放在 [AddAccountViewModel.password] 里，并在保存/离开页面时清空。
 */
data class AddAccountUiState(
    /** 当前步骤：1=邮箱与密码，2=服务器配置，3=验证并保存 */
    val step: Int = AddAccountViewModel.STEP_EMAIL,
    val email: String = "",
    val alias: String = "",
    /** 选中的服务商预设 id，空串表示用户未选择（不自动填充） */
    val presetId: String = "",
    val authType: AuthType = AuthType.APP_PASSWORD,
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: MailSecurity = MailSecurity.SSL_TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: MailSecurity = MailSecurity.SSL_TLS,
    /** 自动探测中 */
    val probing: Boolean = false,
    val probeMessage: String? = null,
    val probeSuccess: Boolean = false,
    /** 连接验证中 */
    val verifying: Boolean = false,
    val verifyMessage: String? = null,
    val verifySuccess: Boolean = false,
    /** 保存中 */
    val saving: Boolean = false,
    /** 当前步骤的校验/操作错误 */
    val error: String? = null,
    /** 手机扫码配置面板是否展开 */
    val pairingVisible: Boolean = false,
    /** 扫码配对服务状态 */
    val pairing: PairingState = PairingState(),
) {
    /** 选中预设对应的服务商提示文案（未选择时返回空串） */
    val authHint: String
        get() = if (presetId.isEmpty()) "" else ProviderPresets.byId(presetId).authHint
}

/** 「添加账户」页的一次性事件 */
sealed interface AddAccountEvent {
    /** 账户已成功保存（屏幕据此振动并回调 onDone） */
    data object Saved : AddAccountEvent

    /** 一般提示（振动 + 文案） */
    data class Message(val text: String, val success: Boolean) : AddAccountEvent
}

/**
 * Outlook / Office 365 的 OAuth2 设备码授权界面状态。
 *
 * **安全约定：本状态类不含任何令牌内容**（与密码同理，避免 `toString()` 或快照日志泄露）。
 * 授权得到的令牌保存在 [AddAccountViewModel] 的私有字段里，界面只能看到
 * 「是否已授权」与「授权有效期」。
 */
data class OAuthAuthUiState(
    /** 是否已配置 Microsoft 客户端 ID（未配置时界面应引导去设置页填写） */
    val configured: Boolean = true,
    /** 正在申请设备码 */
    val requesting: Boolean = false,
    /** 正在等待用户在浏览器完成授权 */
    val waiting: Boolean = false,
    /** 用户短码（展示给用户，在手机浏览器输入） */
    val userCode: String? = null,
    /** 授权页地址（可渲染成二维码） */
    val verificationUri: String? = null,
    /** 授权页二维码（渲染失败时为 null，此时只显示网址） */
    val qrBitmap: Bitmap? = null,
    /** 进度文案 */
    val statusText: String? = null,
    /** 已授权的 access token 过期时刻（0 表示尚未授权）；仅用于显示"有效期至" */
    val authorizedUntilMillis: Long = 0L,
    val error: String? = null,
) {
    val authorized: Boolean
        get() = authorizedUntilMillis > 0L
}

/**
 * 「添加账户」页 ViewModel。
 *
 * 表单之所以拆成三步，是因为手表端的输入成本极高：每一步只让用户面对 1~2 个输入框，
 * 并用「服务商预设 / 自动探测 / 手机扫码」三种方式尽量把输入量降到最低。
 *
 * 用户名固定使用邮箱地址（IMAP/SMTP 的 LOGIN 命令即使用该地址），
 * 因此界面不提供「用户名」输入框，避免出现「用户名与邮箱不一致」这种常见配置错误。
 */
class AddAccountViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(AddAccountUiState())
    val uiState: StateFlow<AddAccountUiState> = _uiState.asStateFlow()

    /**
     * 密码 / 授权码 / OAuth2 令牌（内存态）。
     *
     * 单独成一个流：不进入 [AddAccountUiState]，避免被 `toString()` 或快照日志带出去。
     */
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _events = MutableSharedFlow<AddAccountEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AddAccountEvent> = _events.asSharedFlow()

    // ------------------------------------------------------------------
    // OAuth2（Outlook / Office 365）授权
    // ------------------------------------------------------------------

    private val _oauthState = MutableStateFlow(
        OAuthAuthUiState(configured = container.oauthTokens.isConfigured()),
    )
    val oauthState: StateFlow<OAuthAuthUiState> = _oauthState.asStateFlow()

    /**
     * 授权得到的令牌（**私有字段**）。
     *
     * 绝不放进任何 data class：`toString()` 会打印全部字段，
     * 一旦有人写出 `Logs.d(state)` 就会把 access/refresh token 写进日志。
     */
    private var oauthTokens: OAuthTokens? = null

    /** 正在进行的授权协程（取消 / 离页时终止轮询） */
    private var oauthJob: Job? = null

    init {
        // 订阅扫码配对状态（网页端每保存一个账户，submittedCount 会自增）
        viewModelScope.launch {
            container.pairing.state.collect { pairing ->
                _uiState.update { it.copy(pairing = pairing) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 会话复位
    // ------------------------------------------------------------------

    /**
     * 复位添加账户会话。
     *
     * 覆盖层没有独立的 ViewModelStore，`viewModel()` 的实例会在 Activity 生命周期内复用；
     * 每次进入本页都必须清空上一次的表单与内存中的密码，并确保旧的扫码服务已停止。
     */
    fun resetSession() {
        runCatching { container.pairing.stop() }
        cancelMicrosoftAuth()
        clearPassword()
        _uiState.value = AddAccountUiState()
    }

    // ------------------------------------------------------------------
    // 字段编辑
    // ------------------------------------------------------------------

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onAliasChange(value: String) {
        _uiState.update { it.copy(alias = value) }
    }

    fun onPasswordChange(value: String) {
        _password.value = value
        _uiState.update { it.copy(error = null) }
    }

    fun onAuthTypeChange(type: AuthType) {
        _uiState.update { state ->
            // 切到 OAuth2（Outlook）且服务器地址还空着时，先用 Outlook 预设填上：
            // 让用户在步骤 2 看到正确的主机与端口（Outlook 的 SMTP 必须 587 + STARTTLS，
            // 而表单默认是 465 + SSL/TLS，不填会直接失败）
            val fillFromPreset = type == AuthType.OAUTH2 &&
                state.imapHost.isBlank() &&
                state.smtpHost.isBlank()
            val preset = ProviderPresets.OUTLOOK.config
            state.copy(
                authType = type,
                error = null,
                imapHost = if (fillFromPreset) preset.imapHost else state.imapHost,
                imapPort = if (fillFromPreset) preset.imapPort.toString() else state.imapPort,
                imapSecurity = if (fillFromPreset) preset.imapSecurity else state.imapSecurity,
                smtpHost = if (fillFromPreset) preset.smtpHost else state.smtpHost,
                smtpPort = if (fillFromPreset) preset.smtpPort.toString() else state.smtpPort,
                smtpSecurity = if (fillFromPreset) preset.smtpSecurity else state.smtpSecurity,
                presetId = if (fillFromPreset) ProviderPresets.OUTLOOK.id else state.presetId,
            )
        }
        refreshOAuthConfigured()
    }

    // ------------------------------------------------------------------
    // OAuth2：设备码授权动作
    // ------------------------------------------------------------------

    /** 刷新「是否已配置客户端 ID」（用户可能刚去设置页填过） */
    fun refreshOAuthConfigured() {
        val configured = container.oauthTokens.isConfigured()
        _oauthState.update { it.copy(configured = configured) }
    }

    /**
     * 申请设备码并开始等待授权（设备码流：手表只显示短码，用户在手机浏览器完成登录）。
     *
     * 授权成功后令牌存入私有字段，界面只显示「已授权 + 有效期」。
     */
    fun startMicrosoftAuth() {
        if (oauthJob?.isActive == true) return

        if (!container.oauthTokens.isConfigured()) {
            _oauthState.update {
                it.copy(configured = false, error = OAuthTokenService.MSG_NO_CLIENT_ID)
            }
            return
        }

        _oauthState.value = OAuthAuthUiState(configured = true, requesting = true, statusText = "正在获取授权码…")

        oauthJob = viewModelScope.launch {
            val info = container.oauthTokens.requestDeviceCode().getOrElse { error ->
                Logs.w(TAG, "申请 Microsoft 设备码失败：${error.javaClass.simpleName}")
                _oauthState.update {
                    it.copy(requesting = false, statusText = null, error = error.userText("获取授权码失败"))
                }
                return@launch
            }

            // 二维码在 IO 线程生成：ZXing 编码 + 位图分配都不应占用主线程
            val qr = withContext(Dispatchers.IO) {
                runCatching { QrCodeRenderer.render(info.verificationUri, QR_SIZE_PX) }.getOrNull()
            }

            Logs.i(TAG, "已获取设备码，等待用户在浏览器授权")
            _oauthState.update {
                it.copy(
                    requesting = false,
                    waiting = true,
                    userCode = info.userCode,
                    verificationUri = info.verificationUri,
                    qrBitmap = qr,
                    statusText = "等待授权…",
                    error = null,
                )
            }

            container.oauthTokens.awaitAuthorization(info) { status ->
                _oauthState.update {
                    it.copy(
                        statusText = status.toUiText(),
                        waiting = status !is DeviceCodeStatus.Authorized,
                    )
                }
            }.fold(
                onSuccess = { tokens ->
                    // 令牌只进私有字段
                    oauthTokens = tokens
                    // OAuth2 账户不使用密码，授权成功后立即清掉内存里可能残留的密码
                    clearPassword()
                    Logs.i(TAG, "Microsoft 授权成功，有效期至 ${tokens.expiresAtMillis}")
                    _oauthState.update {
                        it.copy(
                            waiting = false,
                            authorizedUntilMillis = tokens.expiresAtMillis,
                            statusText = "授权成功",
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    Logs.w(TAG, "Microsoft 授权失败：${error.javaClass.simpleName}")
                    _oauthState.update {
                        it.copy(waiting = false, statusText = null, error = error.userText("授权失败"))
                    }
                },
            )
        }
    }

    /** 取消授权（终止轮询并丢弃已获得的令牌） */
    fun cancelMicrosoftAuth() {
        oauthJob?.cancel()
        oauthJob = null
        oauthTokens = null
        _oauthState.value = OAuthAuthUiState(configured = container.oauthTokens.isConfigured())
    }

    /** 把授权进度映射成界面文案（不含任何令牌内容） */
    private fun DeviceCodeStatus.toUiText(): String = when (this) {
        is DeviceCodeStatus.Waiting ->
            if (secondsLeft > 0) "等待授权…（剩余 ${secondsLeft}s）" else "等待授权…"
        is DeviceCodeStatus.SlowDown -> "等待授权…"
        DeviceCodeStatus.Authorized -> "授权成功"
        DeviceCodeStatus.Declined -> "已在浏览器中拒绝授权"
        DeviceCodeStatus.Expired -> "授权码已过期，请重新获取"
        is DeviceCodeStatus.Failed -> message
    }

    /** 选择服务商预设：立即用它填充服务器字段，用户仍可在步骤 2 手动修改 */
    fun selectPreset(preset: ProviderPreset) {
        _uiState.update {
            it.copy(
                presetId = preset.id,
                imapHost = preset.config.imapHost,
                imapPort = preset.config.imapPort.toString(),
                imapSecurity = preset.config.imapSecurity,
                smtpHost = preset.config.smtpHost,
                smtpPort = preset.config.smtpPort.toString(),
                smtpSecurity = preset.config.smtpSecurity,
                error = null,
                probeMessage = null,
                probeSuccess = false,
            )
        }
    }

    fun onImapHostChange(value: String) = _uiState.update { it.copy(imapHost = value, error = null) }

    fun onImapPortChange(value: String) = _uiState.update { it.copy(imapPort = value, error = null) }

    fun onImapSecurityChange(security: MailSecurity) =
        _uiState.update { it.copy(imapSecurity = security, error = null) }

    fun onSmtpHostChange(value: String) = _uiState.update { it.copy(smtpHost = value, error = null) }

    fun onSmtpPortChange(value: String) = _uiState.update { it.copy(smtpPort = value, error = null) }

    fun onSmtpSecurityChange(security: MailSecurity) =
        _uiState.update { it.copy(smtpSecurity = security, error = null) }

    // ------------------------------------------------------------------
    // 步骤切换
    // ------------------------------------------------------------------

    /** 下一步：当前步骤校验通过才会前进 */
    fun nextStep() {
        val state = _uiState.value
        when (state.step) {
            STEP_EMAIL -> {
                AccountFormValidator.validateEmail(state.email.trim())?.let { return fail(it) }
                if (state.authType != AuthType.OAUTH2 && _password.value.isEmpty()) {
                    return fail("请输入密码或授权码")
                }
                // 用户没手动选预设时，按域名自动匹配一次；只在服务器字段为空时填充，
                // 避免覆盖用户已经改好的配置
                applyDetectedPresetIfEmpty()
                _uiState.update { it.copy(step = STEP_SERVER, error = null) }
            }

            STEP_SERVER -> {
                AccountFormValidator.validateServer(_uiState.value.imapHost, _uiState.value.imapPort)
                    ?.let { return fail("IMAP：$it") }
                AccountFormValidator.validateServer(_uiState.value.smtpHost, _uiState.value.smtpPort)
                    ?.let { return fail("SMTP：$it") }
                _uiState.update { it.copy(step = STEP_CONFIRM, error = null) }
            }

            else -> Unit
        }
    }

    /** 上一步 */
    fun prevStep() {
        _uiState.update {
            val target = (it.step - 1).coerceAtLeast(STEP_EMAIL)
            it.copy(step = target, error = null)
        }
    }

    private fun applyDetectedPresetIfEmpty() {
        _uiState.update { state ->
            if (state.imapHost.isNotBlank() && state.smtpHost.isNotBlank()) return@update state
            val preset = ProviderPresets.detect(state.email.trim())
            if (preset.config.imapHost.isBlank() && preset.config.smtpHost.isBlank()) {
                // 企业/自建：没有任何可填的默认值，保持原样
                state.copy(presetId = if (state.presetId.isEmpty()) preset.id else state.presetId)
            } else {
                state.copy(
                    presetId = if (state.presetId.isEmpty()) preset.id else state.presetId,
                    imapHost = state.imapHost.ifBlank { preset.config.imapHost },
                    imapPort = if (state.imapHost.isBlank()) {
                        preset.config.imapPort.toString()
                    } else {
                        state.imapPort
                    },
                    imapSecurity = preset.config.imapSecurity,
                    smtpHost = state.smtpHost.ifBlank { preset.config.smtpHost },
                    smtpPort = if (state.smtpHost.isBlank()) {
                        preset.config.smtpPort.toString()
                    } else {
                        state.smtpPort
                    },
                    smtpSecurity = preset.config.smtpSecurity,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 自动探测 / 验证
    // ------------------------------------------------------------------

    /** 自动探测服务器配置（按预设 → 域名推导 → 企业内部前缀的顺序逐一尝试） */
    fun autoProbe() {
        val state = _uiState.value
        AccountFormValidator.validateEmail(state.email.trim())?.let { return fail(it) }
        if (state.authType != AuthType.OAUTH2 && _password.value.isEmpty()) {
            return fail("请先输入密码或授权码，再自动探测")
        }
        if (state.probing) return

        _uiState.update { it.copy(probing = true, probeMessage = null, probeSuccess = false, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                // 只把密码交给探测层，绝不写入日志
                container.probe.probe(state.email.trim(), AccountSecrets(password = _password.value))
            }
            result.fold(
                onSuccess = { probeResult ->
                    probeResult.fold(
                        onSuccess = { config -> onProbeSuccess(config) },
                        onFailure = { t ->
                            val text = t.userText("自动探测失败")
                            Logs.w(TAG, "自动探测失败：${t.javaClass.simpleName}")
                            _uiState.update {
                                it.copy(probing = false, probeSuccess = false, probeMessage = "探测失败：$text")
                            }
                        },
                    )
                },
                onFailure = { t ->
                    Logs.w(TAG, "自动探测异常：${t.javaClass.simpleName}")
                    _uiState.update {
                        it.copy(probing = false, probeSuccess = false, probeMessage = "探测失败：${t.userText("未知错误")}")
                    }
                },
            )
        }
    }

    private fun onProbeSuccess(config: ServerConfig) {
        Logs.i(TAG, "自动探测成功：IMAP ${config.imapHost}:${config.imapPort} / SMTP ${config.smtpHost}:${config.smtpPort}")
        _uiState.update {
            it.copy(
                probing = false,
                probeSuccess = true,
                probeMessage = "探测成功：已回填服务器配置",
                error = null,
                imapHost = config.imapHost,
                imapPort = config.imapPort.toString(),
                imapSecurity = config.imapSecurity,
                smtpHost = config.smtpHost,
                smtpPort = config.smtpPort.toString(),
                smtpSecurity = config.smtpSecurity,
            )
        }
    }

    /** 验证当前配置能否真正登录（IMAP 与 SMTP 都要通过） */
    fun verify() {
        val state = _uiState.value
        buildConfigOrNull()?.let { config ->
            if (state.verifying) return
            _uiState.update { it.copy(verifying = true, verifyMessage = null, verifySuccess = false, error = null) }
            viewModelScope.launch {
                val result = runCatching {
                    container.probe.verify(config, state.email.trim(), AccountSecrets(password = _password.value))
                }
                result.fold(
                    onSuccess = { verifyResult ->
                        verifyResult.fold(
                            onSuccess = {
                                Logs.i(TAG, "连接验证通过：${config.imapHost} / ${config.smtpHost}")
                                _uiState.update {
                                    it.copy(verifying = false, verifySuccess = true, verifyMessage = "验证通过，配置可用")
                                }
                            },
                            onFailure = { t ->
                                Logs.w(TAG, "连接验证失败：${t.javaClass.simpleName}")
                                _uiState.update {
                                    it.copy(
                                        verifying = false,
                                        verifySuccess = false,
                                        verifyMessage = "验证失败：${t.userText("请检查服务器配置")}",
                                    )
                                }
                            },
                        )
                    },
                    onFailure = { t ->
                        Logs.w(TAG, "连接验证异常：${t.javaClass.simpleName}")
                        _uiState.update {
                            it.copy(
                                verifying = false,
                                verifySuccess = false,
                                verifyMessage = "验证失败：${t.userText("未知错误")}",
                            )
                        }
                    },
                )
            }
        } ?: run {
            val error = AccountFormValidator.validateForm(
                email = state.email.trim(),
                password = _password.value,
                httpHost = state.imapHost,
                httpPort = state.imapPort,
                smtpHost = state.smtpHost,
                smtpPort = state.smtpPort,
                requirePassword = state.authType != AuthType.OAUTH2,
            ) ?: "请先补全服务器配置"
            fail(error)
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /** 校验并保存账户；成功后清空内存中的密码并发出 [AddAccountEvent.Saved] */
    fun save() {
        val state = _uiState.value
        val password = _password.value
        val isOAuth = state.authType == AuthType.OAUTH2
        val oauthPreset = ProviderPresets.OUTLOOK.config

        // 服务器地址：表单填了就用表单的；留空且是 OAuth2（Outlook）时整侧回退到预设 ——
        // 包括端口与加密方式，因为 Outlook 的 SMTP 必须 587 + STARTTLS，
        // 而表单默认是 465 + SSL/TLS，只换主机不换端口必然连不上。
        val formImapHost = AccountFormValidator.normalizeHost(state.imapHost)
        val formSmtpHost = AccountFormValidator.normalizeHost(state.smtpHost)
        val useImapPreset = formImapHost.isEmpty() && isOAuth
        val useSmtpPreset = formSmtpHost.isEmpty() && isOAuth
        val imapHost = if (useImapPreset) oauthPreset.imapHost else formImapHost
        val imapPort = if (useImapPreset) oauthPreset.imapPort else state.imapPort.trim().toIntOrNull() ?: 993
        val imapSecurity = if (useImapPreset) oauthPreset.imapSecurity else state.imapSecurity
        val smtpHost = if (useSmtpPreset) oauthPreset.smtpHost else formSmtpHost
        val smtpPort = if (useSmtpPreset) oauthPreset.smtpPort else state.smtpPort.trim().toIntOrNull() ?: 465
        val smtpSecurity = if (useSmtpPreset) oauthPreset.smtpSecurity else state.smtpSecurity

        // OAuth2 没有密码，凭据来自设备码授权；未授权不得保存
        val error = when {
            isOAuth && oauthTokens == null -> "请先完成 Microsoft 账号授权"
            isOAuth -> AccountFormValidator.validateEmail(state.email.trim())
                ?: AccountFormValidator.validateServer(imapHost, imapPort.toString())
                ?: AccountFormValidator.validateServer(smtpHost, smtpPort.toString())

            else -> AccountFormValidator.validateForm(
                email = state.email.trim(),
                password = password,
                httpHost = state.imapHost,
                httpPort = state.imapPort,
                smtpHost = state.smtpHost,
                smtpPort = state.smtpPort,
                requirePassword = true,
            )
        }
        if (error != null) return fail(error)
        if (state.saving) return

        // 令牌在本方法内只被读取一次并立即转成凭据容器，不进任何 data class
        val tokens = if (isOAuth) oauthTokens else null

        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val email = state.email.trim()
                val account = Account(
                    email = email,
                    alias = state.alias.trim(),
                    authType = state.authType,
                    // IMAP/SMTP 登录名固定为邮箱地址
                    imapHost = imapHost,
                    imapPort = imapPort,
                    imapSecurity = imapSecurity,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    smtpSecurity = smtpSecurity,
                    colorIndex = Account.colorIndexFor(email),
                )
                val secrets = if (tokens != null) {
                    AccountSecrets(oauth = tokens)
                } else {
                    AccountSecrets(password = password)
                }

                // 同一邮箱再次添加视为「重新配置」：覆盖原账户的服务器与凭据，
                // 而不是撞 accounts.email 的 UNIQUE 约束（那会只回一句"保存失败"）
                val existing = container.accounts.accounts.value
                    .firstOrNull { it.email.equals(email, ignoreCase = true) }
                val savedId = if (existing != null) {
                    val updated = account.copy(
                        id = existing.id,
                        colorIndex = existing.colorIndex,
                        notificationsEnabled = existing.notificationsEnabled,
                        createdAt = existing.createdAt,
                        lastSyncAt = existing.lastSyncAt,
                    )
                    if (container.accounts.update(updated, secrets)) existing.id else 0L
                } else {
                    container.accounts.add(account, secrets)
                }
                if (savedId <= 0L) throw IllegalStateException("账户写入失败")
                savedId
            }

            result.fold(
                onSuccess = { accountId ->
                    Logs.i(TAG, "账户已保存 id=$accountId（凭据经 Keystore 加密落盘）")
                    // 通知开关与账户表字段保持一致
                    runCatching { container.settings.setAccountNotifications(accountId, true) }
                    runCatching { container.accounts.load() }
                    clearPassword()
                    _uiState.update { it.copy(saving = false, error = null) }
                    _events.tryEmit(AddAccountEvent.Saved)
                },
                onFailure = { t ->
                    Logs.e(TAG, "账户保存失败：${t.javaClass.simpleName}")
                    _uiState.update { it.copy(saving = false) }
                    fail("保存失败，请重试")
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // 安全：内存中的密码清理
    // ------------------------------------------------------------------

    /** 清空内存中的密码字符串（保存后 / 离开页面时调用） */
    fun clearPassword() {
        _password.value = ""
    }

    // ------------------------------------------------------------------
    // 手机扫码配置
    // ------------------------------------------------------------------

    /** 展开扫码面板并在局域网启动一次性配置服务 */
    fun startPairing() {
        _uiState.update { it.copy(pairingVisible = true, pairing = it.pairing.copy(error = null)) }
        viewModelScope.launch {
            // 启动本地 HTTP 服务涉及套接字绑定，放到 IO 线程避免卡住表盘
            val state = withContext(Dispatchers.IO) {
                runCatching { container.pairing.start() }.getOrNull()
            }
            if (state == null) {
                Logs.w(TAG, "扫码配对服务启动失败")
                _uiState.update {
                    it.copy(pairing = it.pairing.copy(error = "无法启动配置服务，请检查网络后重试"))
                }
            } else {
                Logs.i(TAG, "扫码配对服务已启动 port=${state.port}")
                _uiState.update { it.copy(pairing = state) }
            }
        }
    }

    /** 关闭扫码面板并释放端口 */
    fun stopPairing() {
        runCatching { container.pairing.stop() }
            .onFailure { Logs.w(TAG, "扫码配对服务停止异常：${it.javaClass.simpleName}") }
        _uiState.update { it.copy(pairingVisible = false) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 组装当前表单的服务器配置；字段非法时返回 null */
    private fun buildConfigOrNull(): ServerConfig? {
        val state = _uiState.value
        val imapPort = state.imapPort.trim().toIntOrNull() ?: return null
        val smtpPort = state.smtpPort.trim().toIntOrNull() ?: return null
        val imapHost = AccountFormValidator.normalizeHost(state.imapHost)
        val smtpHost = AccountFormValidator.normalizeHost(state.smtpHost)
        if (imapHost.isEmpty() || smtpHost.isEmpty()) return null
        if (!AccountFormValidator.isValidPort(state.imapPort)) return null
        if (!AccountFormValidator.isValidPort(state.smtpPort)) return null
        return ServerConfig(
            imapHost = imapHost,
            imapPort = imapPort,
            imapSecurity = state.imapSecurity,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpSecurity = state.smtpSecurity,
        )
    }

    /** 统一处理「校验失败」：写入错误状态并发出失败事件（屏幕振动提示） */
    private fun fail(message: String) {
        _uiState.update { it.copy(error = message) }
        _events.tryEmit(AddAccountEvent.Message(text = message, success = false))
    }

    /** 把异常翻译成面向用户的中文短句（不打印、不外泄原始堆栈） */
    private fun Throwable.userText(default: String): String =
        (this as? MailError)?.userMessage ?: message?.take(60) ?: default

    override fun onCleared() {
        // 离开页面必须释放监听端口，并清除内存中的密码与令牌
        runCatching { container.pairing.stop() }
        oauthJob?.cancel()
        oauthJob = null
        oauthTokens = null
        clearPassword()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AddAccount"

        /** 授权二维码像素边长（466px 表盘上留出边距，手机近距离可稳定识别） */
        private const val QR_SIZE_PX = 300

        /** 步骤 1：邮箱与密码 */
        const val STEP_EMAIL: Int = 1

        /** 步骤 2：服务器配置 */
        const val STEP_SERVER: Int = 2

        /** 步骤 3：验证并保存 */
        const val STEP_CONFIRM: Int = 3

        /** 步骤总数（界面显示「N/3」） */
        const val STEP_COUNT: Int = 3

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddAccountViewModel(container) }
        }
    }
}
