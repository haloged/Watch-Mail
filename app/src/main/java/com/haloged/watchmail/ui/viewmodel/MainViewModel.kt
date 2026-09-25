package com.haloged.watchmail.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haloged.watchmail.data.local.SettingsRepository
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.local.entity.DraftEntity
import com.haloged.watchmail.data.local.entity.EmailBodyEntity
import com.haloged.watchmail.data.local.entity.EmailEntity
import com.haloged.watchmail.data.local.entity.EncryptionType
import com.haloged.watchmail.data.remote.EmailAutoConfigDetector
import com.haloged.watchmail.data.remote.AutoConfigResult
import com.haloged.watchmail.data.remote.smtp.SmtpSendResult
import com.haloged.watchmail.data.repository.EmailRepository
import com.haloged.watchmail.data.repository.SyncResult
import com.haloged.watchmail.service.EmailSyncWorker
import com.haloged.watchmail.ui.theme.WatchMailColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 撰写邮件预填数据
 * 用于"回复"等场景把收件人/主题/引用正文带入撰写页
 */
data class ComposePrefill(
    val toAddress: String = "",
    val subject: String = "",
    val body: String = "",
    val accountId: Long? = null
)

/**
 * 主ViewModel
 * 管理应用的全局状态和业务逻辑
 *
 * ⚠ 稳定性约定：所有可能触网/触库的协程体一律 catch (Throwable)，
 *   因为 JavaMail / OOM 等抛的是 Error 而非 Exception，漏接会导致 App 闪退。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = EmailRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val autoConfigDetector = EmailAutoConfigDetector()

    // ==================== 账户状态 ====================

    private val _accounts = MutableStateFlow<List<AccountEntity>>(emptyList())
    val accounts: StateFlow<List<AccountEntity>> = _accounts.asStateFlow()

    private val _selectedAccountId = MutableStateFlow<Long?>(null)
    val selectedAccountId: StateFlow<Long?> = _selectedAccountId.asStateFlow()

    // ==================== 邮件状态 ====================

    private val _emails = MutableStateFlow<List<EmailEntity>>(emptyList())
    val emails: StateFlow<List<EmailEntity>> = _emails.asStateFlow()

    private val _selectedEmail = MutableStateFlow<EmailEntity?>(null)
    val selectedEmail: StateFlow<EmailEntity?> = _selectedEmail.asStateFlow()

    private val _emailBody = MutableStateFlow<EmailBodyEntity?>(null)
    val emailBody: StateFlow<EmailBodyEntity?> = _emailBody.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    // ==================== 同步状态 ====================

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    // ==================== 发送状态 ====================

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendResult = MutableStateFlow<SmtpSendResult?>(null)
    val sendResult: StateFlow<SmtpSendResult?> = _sendResult.asStateFlow()

    // ==================== 草稿状态 ====================

    private val _drafts = MutableStateFlow<List<DraftEntity>>(emptyList())
    val drafts: StateFlow<List<DraftEntity>> = _drafts.asStateFlow()

    // ==================== 撰写预填 ====================

    private val _composePrefill = MutableStateFlow<ComposePrefill?>(null)
    val composePrefill: StateFlow<ComposePrefill?> = _composePrefill.asStateFlow()

    // ==================== 应用设置 ====================

    private val _settings = MutableStateFlow(
        com.haloged.watchmail.data.local.AppSettings()
    )
    val settings: StateFlow<com.haloged.watchmail.data.local.AppSettings> = _settings.asStateFlow()

    // ==================== UI状态 ====================

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        observeAccounts()
        observeEmails()
        observeDrafts()
        observeSettings()

        viewModelScope.launch {
            refreshUnreadCount()
        }
    }

    // ==================== 数据订阅 ====================

    private fun observeAccounts() {
        viewModelScope.launch {
            repository.getAllAccountsFlow().collect { accountList ->
                _accounts.value = accountList
            }
        }
    }

    private fun observeEmails() {
        viewModelScope.launch {
            repository.getAllEmailsFlow().collect { emailList ->
                _emails.value = emailList
            }
        }
    }

    private fun observeDrafts() {
        viewModelScope.launch {
            repository.getAllDraftsFlow().collect { draftList ->
                _drafts.value = draftList
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings
                .distinctUntilChanged()   // 仅在实际变化时才重排 WorkManager
                .collect { s ->
                    _settings.value = s
                    try {
                        EmailSyncWorker.enqueuePeriodicSync(
                            getApplication(),
                            s.syncFrequencyMinutes.toLong()
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "重排同步任务失败", t)
                    }
                }
        }
    }

    // ==================== 账户操作 ====================

    /**
     * 添加账户
     * @param onResult 回调成功/失败，UI 据此决定是否返回上一页
     */
    fun addAccount(
        email: String,
        password: String,
        alias: String,
        imapHost: String,
        imapPort: Int,
        imapEncryption: EncryptionType,
        smtpHost: String,
        smtpPort: Int,
        smtpEncryption: EncryptionType,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val colorIndex = _accounts.value.size
                val color = WatchMailColors.colorToLong(WatchMailColors.getAccountColor(colorIndex))

                val accountId = repository.addAccount(
                    email = email,
                    password = password,
                    alias = alias,
                    imapHost = imapHost,
                    imapPort = imapPort,
                    imapEncryption = imapEncryption,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    smtpEncryption = smtpEncryption,
                    color = color
                )

                Log.d(TAG, "账户添加成功: $email, ID=$accountId")

                // 自动同步新账户
                syncAccount(accountId)
                onResult(true, null)

            } catch (t: Throwable) {
                Log.e(TAG, "添加账户失败", t)
                val msg = "添加账户失败: ${t.message ?: t.javaClass.simpleName}"
                _errorMessage.value = msg
                onResult(false, msg)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 删除账户
     */
    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteAccount(accountId)
                Log.d(TAG, "账户删除成功: ID=$accountId")
            } catch (t: Throwable) {
                Log.e(TAG, "删除账户失败", t)
                _errorMessage.value = "删除账户失败: ${t.message}"
            }
        }
    }

    /**
     * 更新账户
     * @param onResult 回调成功/失败
     */
    fun updateAccount(account: AccountEntity, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                repository.updateAccount(account)
                Log.d(TAG, "账户更新成功: ${account.email}")
                onResult(true)
            } catch (t: Throwable) {
                Log.e(TAG, "更新账户失败", t)
                _errorMessage.value = "更新账户失败: ${t.message}"
                onResult(false)
            }
        }
    }

    /**
     * 更新账户密码（加密存储）
     */
    fun updateAccountPassword(accountId: Long, newPassword: String) {
        viewModelScope.launch {
            try {
                repository.updateAccountPassword(accountId, newPassword)
                Log.d(TAG, "账户密码更新成功: ID=$accountId")
            } catch (t: Throwable) {
                Log.e(TAG, "更新密码失败", t)
                _errorMessage.value = "更新密码失败: ${t.message}"
            }
        }
    }

    /**
     * 切换每账户通知开关
     */
    fun setAccountNotificationEnabled(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setAccountNotificationEnabled(accountId, enabled)
                Log.d(TAG, "账户通知开关更新: ID=$accountId, enabled=$enabled")
            } catch (t: Throwable) {
                Log.e(TAG, "更新通知开关失败", t)
            }
        }
    }

    /**
     * 切换每账户启用状态
     */
    fun setAccountEnabled(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setAccountEnabled(accountId, enabled)
                Log.d(TAG, "账户启用状态更新: ID=$accountId, enabled=$enabled")
            } catch (t: Throwable) {
                Log.e(TAG, "更新启用状态失败", t)
            }
        }
    }

    /**
     * 选择账户（用于筛选）
     */
    fun selectAccount(accountId: Long?) {
        _selectedAccountId.value = accountId
    }

    /**
     * 自动探测邮箱配置
     */
    fun detectEmailConfig(email: String, onResult: (AutoConfigResult) -> Unit) {
        viewModelScope.launch {
            val result = try {
                autoConfigDetector.detectConfig(email)
            } catch (t: Throwable) {
                Log.e(TAG, "配置探测异常", t)
                AutoConfigResult.Error(t.message ?: "探测失败")
            }
            onResult(result)
        }
    }

    /**
     * 通过「手机扫码配对」创建账户
     *
     * 若手机端留空了服务器地址，则在手表端自动探测（Gmail/Outlook/QQ/163 等）；
     * 探测失败则回落到通用 imap./smtp. 域名配置，仍允许保存并提示用户核对。
     */
    fun createAccountFromPairing(
        payload: com.haloged.watchmail.data.remote.pairing.PairingAccountPayload,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                var imapHost = payload.imapHost
                var imapPort = payload.imapPort
                var imapEncryption = payload.imapEncryption
                var smtpHost = payload.smtpHost
                var smtpPort = payload.smtpPort
                var smtpEncryption = payload.smtpEncryption

                if (payload.needsAutoDetect) {
                    Log.d(TAG, "手机未指定服务器，开始自动探测: ${payload.email}")
                    when (val det = try {
                        autoConfigDetector.detectConfig(payload.email)
                    } catch (t: Throwable) {
                        AutoConfigResult.Error(t.message ?: "探测异常")
                    }) {
                        is AutoConfigResult.Success -> {
                            val cfg = det.config
                            imapHost = imapHost ?: cfg.imapHost
                            imapPort = imapPort ?: cfg.imapPort
                            imapEncryption = imapEncryption ?: cfg.imapEncryption
                            smtpHost = smtpHost ?: cfg.smtpHost
                            smtpPort = smtpPort ?: cfg.smtpPort
                            smtpEncryption = smtpEncryption ?: cfg.smtpEncryption
                            Log.d(TAG, "自动探测成功: imap=$imapHost:$imapPort, smtp=$smtpHost:$smtpPort")
                        }
                        is AutoConfigResult.Error -> {
                            val fallback = com.haloged.watchmail.util.EmailConfigUtil
                                .generateGenericConfig(payload.email.substringAfter("@"))
                            imapHost = imapHost ?: fallback.imapHost
                            imapPort = imapPort ?: fallback.imapPort
                            imapEncryption = imapEncryption ?: fallback.imapEncryption
                            smtpHost = smtpHost ?: fallback.smtpHost
                            smtpPort = smtpPort ?: fallback.smtpPort
                            smtpEncryption = smtpEncryption ?: fallback.smtpEncryption
                            Log.w(TAG, "自动探测失败，使用通用配置: ${det.message}")
                        }
                    }
                }

                if (imapHost.isNullOrBlank() || smtpHost.isNullOrBlank() ||
                    imapPort == null || smtpPort == null ||
                    imapEncryption == null || smtpEncryption == null
                ) {
                    onResult(false, "服务器配置不完整，请在手机端手动填写")
                    return@launch
                }

                addAccount(
                    email = payload.email,
                    password = payload.password,
                    alias = payload.alias.ifBlank { payload.email.substringBefore("@") },
                    imapHost = imapHost,
                    imapPort = imapPort,
                    imapEncryption = imapEncryption,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    smtpEncryption = smtpEncryption,
                    onResult = onResult
                )

            } catch (t: Throwable) {
                Log.e(TAG, "扫码配对创建账户失败", t)
                val msg = "创建账户失败: ${t.message ?: t.javaClass.simpleName}"
                _errorMessage.value = msg
                onResult(false, msg)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ==================== 应用设置 ====================

    /** 更新同步频率（分钟），会自动重排 WorkManager */
    fun setSyncFrequency(minutes: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.setSyncFrequency(minutes)
            } catch (t: Throwable) {
                Log.e(TAG, "保存同步频率失败", t)
            }
        }
    }

    /** 更新全局通知开关 */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setNotificationsEnabled(enabled)
            } catch (t: Throwable) {
                Log.e(TAG, "保存通知开关失败", t)
            }
        }
    }

    /** 更新自动发送草稿开关 */
    fun setAutoSendDrafts(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setAutoSendDrafts(enabled)
            } catch (t: Throwable) {
                Log.e(TAG, "保存自动发草稿开关失败", t)
            }
        }
    }

    /** 更新界面显示大小（百分比，100 = 1.0x 默认），立即生效 */
    fun setUiScalePercent(percent: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.setUiScalePercent(percent)
                Log.d(TAG, "界面缩放更新为 $percent%")
            } catch (t: Throwable) {
                Log.e(TAG, "保存界面缩放失败", t)
            }
        }
    }

    // ==================== 邮件同步 ====================

    /**
     * 同步所有账户
     * 全链路 Throwable 兜底：任何 Error（OOM / NoClassDefFoundError）都不能冒泡到主线程
     */
    fun syncAllAccounts() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncError.value = null

            try {
                val results = repository.syncAllAccounts()

                val errors = results.filter { it.value is SyncResult.Error }
                if (errors.isNotEmpty()) {
                    val errorMessages = errors.map { (accountId, result) ->
                        val account = repository.getAccountById(accountId)
                        "${account?.email ?: "未知账户"}: ${(result as SyncResult.Error).message}"
                    }
                    _syncError.value = errorMessages.joinToString("\n")
                }

                refreshUnreadCount()

            } catch (t: Throwable) {
                Log.e(TAG, "同步失败", t)
                _syncError.value = "同步失败: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * 同步单个账户
     */
    fun syncAccount(accountId: Long) {
        viewModelScope.launch {
            try {
                _isSyncing.value = true

                val account = repository.getAccountById(accountId)
                if (account != null) {
                    when (val result = repository.syncAccount(account)) {
                        is SyncResult.Success -> {
                            Log.d(TAG, "账户同步成功: ${account.email}, 新增${result.newCount}封")
                            refreshUnreadCount()
                        }
                        is SyncResult.Error -> {
                            _syncError.value = "${account.email}: ${result.message}"
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "账户同步失败: $accountId", t)
                _syncError.value = "同步失败: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // ==================== 邮件操作 ====================

    /** 当前邮件详情的订阅 Job（退出详情页必须取消，否则一直挂着） */
    private var emailDetailJob: Job? = null

    /**
     * 选择邮件（查看详情）
     * 通过邮件 ID 加载单封邮件，并订阅其实时更新
     */
    fun selectEmail(emailId: Long) {
        // 先取消上一次订阅，避免多个 collect 叠加
        emailDetailJob?.cancel()
        emailDetailJob = viewModelScope.launch {
            try {
                // 先从本地快速定位，避免闪白
                _selectedEmail.value = _emails.value.find { it.id == emailId }
                    ?: repository.getEmailById(emailId)

                // LRU 触达：只在订阅前做一次
                // ⚠ 千万不要在 collect 内部写库 —— Room 会因表变更重新发射 Flow，
                //   形成「发射→写库→发射」无限循环，最终 ANR/看门狗杀进程（表现为闪退）
                repository.getEmailByIdAndTouch(emailId)

                // 订阅该邮件的实时更新（如已读状态变化）
                repository.getEmailByIdFlow(emailId).collect { email ->
                    _selectedEmail.value = email
                }
            } catch (t: Throwable) {
                Log.e(TAG, "获取邮件详情失败", t)
                _errorMessage.value = "加载邮件失败: ${t.message}"
            }
        }
    }

    /**
     * 取消邮件订阅（退出详情页时调用）
     */
    fun clearSelectedEmail() {
        emailDetailJob?.cancel()
        emailDetailJob = null
        _selectedEmail.value = null
        _emailBody.value = null
    }

    /**
     * 加载邮件正文（本地优先，未命中则按需下载）
     */
    fun loadEmailBody(emailId: Long) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // 先尝试从本地获取
                var body = repository.getEmailBody(emailId)

                if (body == null) {
                    // 本地没有，从服务器下载
                    val success = repository.downloadEmailBody(emailId)
                    if (success) {
                        body = repository.getEmailBody(emailId)
                    }
                }

                _emailBody.value = body

            } catch (t: Throwable) {
                Log.e(TAG, "加载邮件正文失败", t)
                _errorMessage.value = "加载正文失败: ${t.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 标记邮件为已读
     */
    fun markEmailAsRead(emailId: Long, isRead: Boolean = true) {
        viewModelScope.launch {
            try {
                repository.markEmailAsRead(emailId, isRead)
                refreshUnreadCount()
            } catch (t: Throwable) {
                Log.e(TAG, "标记已读失败", t)
            }
        }
    }

    /**
     * 删除邮件
     */
    fun deleteEmail(emailId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteEmail(emailId)
                refreshUnreadCount()
                Log.d(TAG, "邮件删除成功: ID=$emailId")
            } catch (t: Throwable) {
                Log.e(TAG, "删除邮件失败", t)
                _errorMessage.value = "删除失败: ${t.message}"
            }
        }
    }

    // ==================== 邮件发送 ====================

    /**
     * 发送邮件
     * 发送失败时自动转存为草稿，联网后由后台任务补发
     */
    fun sendEmail(accountId: Long, toAddress: String, subject: String, body: String) {
        viewModelScope.launch {
            _isSending.value = true
            _sendResult.value = null

            try {
                val result = repository.sendEmail(accountId, toAddress, subject, body)
                _sendResult.value = result

                when (result) {
                    is SmtpSendResult.Success -> {
                        Log.d(TAG, "邮件发送成功")
                    }
                    is SmtpSendResult.Error -> {
                        Log.e(TAG, "邮件发送失败: ${result.message}")
                        // 离线/失败自动暂存草稿，联网后自动发送
                        try {
                            val draftId = repository.saveDraft(accountId, toAddress, subject, body)
                            Log.d(TAG, "已转存为离线草稿: ID=$draftId")
                        } catch (t: Throwable) {
                            Log.e(TAG, "转存草稿失败", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "发送邮件异常", t)
                _sendResult.value = SmtpSendResult.Error("发送失败: ${t.message ?: t.javaClass.simpleName}")
                try {
                    repository.saveDraft(accountId, toAddress, subject, body)
                } catch (t2: Throwable) {
                    Log.e(TAG, "转存草稿失败", t2)
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * 保存草稿
     */
    fun saveDraft(accountId: Long, toAddress: String, subject: String, body: String) {
        viewModelScope.launch {
            try {
                repository.saveDraft(accountId, toAddress, subject, body)
                Log.d(TAG, "草稿保存成功")
                _errorMessage.value = "草稿已保存"
            } catch (t: Throwable) {
                Log.e(TAG, "保存草稿失败", t)
                _errorMessage.value = "保存草稿失败: ${t.message}"
            }
        }
    }

    /**
     * 发送待发送的草稿（联网后自动补发）
     */
    fun sendPendingDrafts() {
        viewModelScope.launch {
            try {
                val sentCount = repository.sendPendingDrafts()
                if (sentCount > 0) {
                    Log.d(TAG, "发送了${sentCount}封待发送草稿")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "发送草稿失败", t)
            }
        }
    }

    /**
     * 删除草稿
     */
    fun deleteDraft(draftId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteDraft(draftId)
            } catch (t: Throwable) {
                Log.e(TAG, "删除草稿失败", t)
            }
        }
    }

    /**
     * 立即发送指定草稿（成功后删除原草稿）
     */
    fun sendDraft(draft: DraftEntity) {
        sendEmail(draft.accountId, draft.toAddress, draft.subject, draft.body)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            if (_sendResult.value is SmtpSendResult.Success) {
                try {
                    repository.deleteDraft(draft.id)
                } catch (t: Throwable) {
                    Log.e(TAG, "删除已发送草稿失败", t)
                }
            }
        }
    }

    // ==================== 撰写预填 ====================

    /** 设置撰写预填（回复 / 草稿续写） */
    fun setComposePrefill(prefill: ComposePrefill) {
        _composePrefill.value = prefill
    }

    /** 清除撰写预填 */
    fun clearComposePrefill() {
        _composePrefill.value = null
    }

    /**
     * 生成回复预填数据
     * 主题加 Re: 前缀，正文引用原文
     */
    fun prepareReply(email: EmailEntity, originalBody: String?) {
        val quoted = buildString {
            append("\n\n---------- 原始邮件 ----------\n")
            append("发件人: ${email.fromName?.takeIf { it.isNotBlank() } ?: email.fromAddress}\n")
            append(
                "时间: " + java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date(email.receivedAt)) + "\n"
            )
            append("主题: ${email.subject}\n\n")
            append(originalBody?.take(500) ?: email.preview)
        }

        val subject = if (email.subject.startsWith("Re:", ignoreCase = true)) {
            email.subject
        } else {
            "Re: ${email.subject}"
        }

        _composePrefill.value = ComposePrefill(
            toAddress = email.fromAddress,
            subject = subject,
            body = quoted,
            accountId = email.accountId
        )
    }

    // ==================== 搜索 ====================

    /**
     * 搜索邮件（空查询恢复全量列表）
     */
    fun searchEmails(query: String) {
        if (query.isBlank()) {
            observeEmails()
            return
        }
        viewModelScope.launch {
            repository.searchEmails(query).collect { results ->
                _emails.value = results
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 刷新未读数量 */
    private suspend fun refreshUnreadCount() {
        try {
            _unreadCount.value = repository.getUnreadCount()
        } catch (t: Throwable) {
            Log.w(TAG, "刷新未读数失败", t)
        }
    }

    /** 清除错误信息 */
    fun clearError() {
        _errorMessage.value = null
        _syncError.value = null
    }

    /** 清除发送结果 */
    fun clearSendResult() {
        _sendResult.value = null
    }

    /** 测试账户连接（IMAP + SMTP） */
    fun testAccountConnection(account: AccountEntity, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                repository.testAccountConnection(account)
            } catch (t: Throwable) {
                Log.e(TAG, "测试连接异常", t)
                false
            }
            onResult(success)
        }
    }
}
