package com.wm.wearmail.ui.screen.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.MailError
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.Contact
import com.wm.wearmail.model.SendState
import com.wm.wearmail.ui.screen.accounts.AccountFormValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 撰写邮件页 UI 状态。
 *
 * 正文长度上限与同步引擎保持一致（超出部分由引擎在发送时截断），
 * UI 只做「警示」，不阻止用户继续输入——手表端丢内容比超长更糟。
 */
data class ComposeMailUiState(
    val loading: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: Long? = null,
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    /** 常用联系人（点一下即填入收件人） */
    val contacts: List<Contact> = emptyList(),
    /** 是否由草稿载入（用于提示文案） */
    val fromDraft: Boolean = false,
    val error: String? = null,
) {
    /** 当前选中的发件账户 */
    val selectedAccount: Account?
        get() = accounts.firstOrNull { it.id == selectedAccountId }

    /** 收件人合法且已选发件账户时才允许发送 */
    val canSend: Boolean
        get() = selectedAccountId != null && AccountFormValidator.isValidEmail(to.trim())

    val bodyLength: Int
        get() = body.length

    val bodyTooLong: Boolean
        get() = body.length > MAX_BODY_CHARS

    /** 是否已输入内容（返回时用于二次确认） */
    val hasContent: Boolean
        get() = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank()

    companion object {
        /** 正文长度上限（字符） */
        const val MAX_BODY_CHARS: Int = 500
    }
}

/**
 * 撰写邮件页 ViewModel。
 *
 * 发送走 [com.wm.wearmail.sync.SyncService.send]：失败时同步引擎会把内容落为草稿，
 * 因此 UI 侧的「重试」优先投递待发送草稿（flushPendingDrafts），
 * 无草稿可投时再直接重发一次。
 *
 * 隐私：日志只记录账户 id 与异常类型，绝不记录收件人、主题与正文。
 */
class ComposeMailViewModel(
    private val container: AppContainer,
    /** 回复场景预填的收件人 */
    private val prefillTo: String?,
    /** 回复场景预填的主题 */
    private val prefillSubject: String?,
    /** 从草稿进入时的草稿 id */
    private val draftId: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeMailUiState())
    val uiState: StateFlow<ComposeMailUiState> = _uiState.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)

    /** 发送状态机（屏幕据此显示转圈/✓/✗ + 振动） */
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    init {
        // 先按预填参数初始化，异步载入账户与草稿后再覆盖
        _uiState.update {
            it.copy(
                to = prefillTo.orEmpty(),
                subject = buildSubject(prefillSubject),
            )
        }

        viewModelScope.launch { loadInitial() }

        // 常用联系人持续更新（发件人自动累积）
        viewModelScope.launch {
            container.contacts.observeFrequent(FREQUENT_LIMIT)
                .catch { t -> Logs.w(TAG, "常用联系人订阅失败：${t.javaClass.simpleName}") }
                .collect { list -> _uiState.update { it.copy(contacts = list) } }
        }
    }

    // ------------------------------------------------------------------
    // 会话复位
    // ------------------------------------------------------------------

    /**
     * 复位本次撰写会话。
     *
     * 覆盖层（撰写页）没有独立的 ViewModelStore，`viewModel()` 拿到的实例会
     * 在 Activity 整个生命周期内复用；因此每次进入本页都必须显式复位，
     * 否则用户会看到上一次撰写的收件人与正文（甚至误发）。
     */
    fun resetSession() {
        _sendState.value = SendState.Idle
        _uiState.update {
            ComposeMailUiState(
                loading = true,
                to = prefillTo.orEmpty(),
                subject = buildSubject(prefillSubject),
            )
        }
        viewModelScope.launch { loadInitial() }
    }

    // ------------------------------------------------------------------
    // 字段编辑
    // ------------------------------------------------------------------

    fun onToChange(value: String) = _uiState.update { it.copy(to = value, error = null) }

    fun onSubjectChange(value: String) = _uiState.update { it.copy(subject = value) }

    fun onBodyChange(value: String) = _uiState.update { it.copy(body = value) }

    /** 切换发件账户 */
    fun selectAccount(accountId: Long) = _uiState.update { it.copy(selectedAccountId = accountId) }

    /** 点选常用联系人 → 填入收件人 */
    fun useContact(contact: Contact) = _uiState.update {
        it.copy(to = contact.address, error = null)
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 发送邮件（首次发送入口） */
    fun send() {
        val state = _uiState.value
        val accountId = state.selectedAccountId
        if (accountId == null) {
            _sendState.value = SendState.Failure("请先选择发件账户")
            return
        }
        val to = state.to.trim()
        if (!AccountFormValidator.isValidEmail(to)) {
            _uiState.update { it.copy(error = "收件人邮箱格式不正确") }
            _sendState.value = SendState.Failure("收件人邮箱格式不正确")
            return
        }
        if (_sendState.value == SendState.Sending) return
        _sendState.value = SendState.Sending
        viewModelScope.launch { deliver(accountId, to) }
    }

    /**
     * 重试发送。
     *
     * 先尝试投递待发送草稿（失败时引擎已落盘，避免重复建草稿）；
     * 若没有可投递的草稿，再退回直接重发当前内容。
     */
    fun retry() {
        if (_sendState.value == SendState.Sending) return
        val state = _uiState.value
        val accountId = state.selectedAccountId
        if (accountId == null) {
            _sendState.value = SendState.Failure("请先选择发件账户")
            return
        }
        _sendState.value = SendState.Sending
        viewModelScope.launch {
            val flushed = runCatching { container.sync.flushPendingDrafts() }.getOrDefault(0)
            if (flushed > 0) {
                Logs.i(TAG, "重试成功：已投递 $flushed 封待发送草稿")
                draftId?.let { id -> runCatching { container.drafts.delete(id) } }
                _sendState.value = SendState.Success
            } else {
                deliver(accountId, state.to.trim())
            }
        }
    }

    /** 清除发送失败状态（用户关闭错误提示） */
    fun dismissSendState() {
        if (_sendState.value !is SendState.Sending) _sendState.value = SendState.Idle
    }

    private suspend fun deliver(accountId: Long, to: String) {
        val state = _uiState.value
        val outcome = runCatching {
            container.sync.send(
                accountId = accountId,
                to = to,
                subject = state.subject.trim(),
                body = state.body,
            )
        }
        outcome.fold(
            onSuccess = { result ->
                result.fold(
                    onSuccess = {
                        Logs.i(TAG, "发送成功 accountId=$accountId")
                        // 记录收件人，下次可直接点选
                        runCatching { container.contacts.record(null, to) }
                        draftId?.let { id -> runCatching { container.drafts.delete(id) } }
                        _sendState.value = SendState.Success
                    },
                    onFailure = { t ->
                        Logs.w(TAG, "发送失败 accountId=$accountId error=${t.javaClass.simpleName}")
                        _sendState.value = SendState.Failure(t.userText("发送失败，内容已存为草稿"))
                    },
                )
            },
            onFailure = { t ->
                Logs.e(TAG, "发送异常 accountId=$accountId error=${t.javaClass.simpleName}")
                _sendState.value = SendState.Failure(t.userText("发送失败，内容已存为草稿"))
            },
        )
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private suspend fun loadInitial() {
        val accounts = runCatching {
            val cached = container.accounts.accounts.value
            if (cached.isNotEmpty()) cached else container.accounts.load()
        }.getOrDefault(emptyList())

        val draft = draftId?.let { id ->
            runCatching { container.drafts.draft(id) }
                .onFailure { Logs.w(TAG, "草稿读取失败 id=$id") }
                .getOrNull()
        }

        _uiState.update { state ->
            val draftAccountId = draft?.accountId?.takeIf { id -> accounts.any { it.id == id } }
            state.copy(
                loading = false,
                accounts = accounts,
                selectedAccountId = draftAccountId ?: state.selectedAccountId ?: accounts.firstOrNull()?.id,
                to = draft?.to?.takeIf { it.isNotBlank() } ?: state.to,
                subject = draft?.subject?.takeIf { it.isNotBlank() } ?: state.subject,
                body = draft?.body?.takeIf { it.isNotBlank() } ?: state.body,
                fromDraft = draft != null,
                error = if (accounts.isEmpty()) "尚未添加邮箱账户，请先在账户页添加" else null,
            )
        }
    }

    /**
     * 回复场景自动补 `Re:`。
     *
     * 已经带前缀（Re: / 回复: / Fwd: / 转发:）的主题不再叠加，
     * 避免出现 `Re: Re: xxx` 或把转发主题错误标成回复。
     */
    private fun buildSubject(raw: String?): String {
        val subject = raw?.trim().orEmpty()
        if (subject.isEmpty()) return ""
        val lower = subject.lowercase()
        val alreadyPrefixed = lower.startsWith("re:") ||
            lower.startsWith("回复:") ||
            lower.startsWith("fwd:") ||
            lower.startsWith("fw:") ||
            lower.startsWith("转发:")
        return if (alreadyPrefixed) subject else "Re: $subject"
    }

    /** 把异常翻译成中文短句（原始堆栈不展示、不入日志） */
    private fun Throwable.userText(default: String): String =
        (this as? MailError)?.userMessage ?: message?.take(60) ?: default

    companion object {
        private const val TAG = "ComposeMail"

        /** 常用联系人展示数量（圆形表盘一行放不下更多） */
        private const val FREQUENT_LIMIT = 6

        /**
         * ViewModel 工厂。
         *
         * 比标准写法多带三个预填参数（回复/草稿场景必需），
         * 用法与其它页面一致：`viewModel(factory = ComposeMailViewModel.factory(...))`。
         */
        fun factory(
            container: AppContainer,
            prefillTo: String?,
            prefillSubject: String?,
            draftId: Long?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ComposeMailViewModel(container, prefillTo, prefillSubject, draftId) }
        }
    }
}
