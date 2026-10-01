package com.wm.wearmail.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.toMailError
import com.wm.wearmail.model.EmailMeta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 邮件详情状态。
 *
 * @param mail 邮件元数据；null 且 [DetailUiState.mailGone] 为 true 表示本地已无此邮件
 * @param body 正文纯文本；null 表示尚未加载或加载失败
 * @param loadingBody 是否正在加载正文
 * @param loadingMail 是否仍在等待首帧邮件数据（避免把「还没读到」误显示成「已删除」）
 * @param error 面向用户的错误提示
 * @param deleted 本页触发的删除已完成（屏幕层据此返回上一页）
 */
data class DetailUiState(
    val mail: EmailMeta? = null,
    val body: String? = null,
    val loadingBody: Boolean = false,
    val loadingMail: Boolean = true,
    val error: String? = null,
    val deleted: Boolean = false,
) {
    /** 邮件确实已不存在（而非首帧尚未读到），用于显示「邮件已删除」空态 */
    val mailGone: Boolean
        get() = !loadingMail && !deleted && mail == null
}

/** 正文加载的内部状态机 */
private sealed interface BodyState {
    /** 尚未请求（进入详情页时触发） */
    data object Idle : BodyState

    data object Loading : BodyState

    /** 已缓存到内存，避免重复请求网络 */
    data class Loaded(val text: String) : BodyState

    data class Failed(val message: String) : BodyState
}

/**
 * 邮件详情 ViewModel。
 *
 * 正文策略：
 * - [SyncService.loadBody] 内部已实现「先本地缓存、未命中再下载」；
 * - 本 VM 额外在内存里保留一份 [BodyState.Loaded]，避免每次重组/重进页面重复请求；
 * - 正文加载失败时保留错误文案，屏幕层提供「重试」按钮。
 *
 * 安全约定：**不**把正文写入日志，只记录邮件 id 与错误类型。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailDetailViewModel(
    private val container: AppContainer,
    private val emailId: Long,
) : ViewModel() {

    private val bodyState = MutableStateFlow<BodyState>(BodyState.Idle)

    private val deleted = MutableStateFlow(false)

    /** 是否已经从数据库读到过首帧邮件（区分「尚未加载」与「真的不存在」） */
    private val mailObserved = MutableStateFlow(false)

    /** 邮件被外部删除（如从其他页面删除）或本地不存在时为 null */
    private val mailFlow = container.emails
        .observeEmail(emailId)
        .onEach {
            // 首帧一到就置位，之后不再变化
            mailObserved.value = true
        }

    /**
     * 正文来源：数据库缓存（Flow）与内存加载状态（StateFlow）合并。
     *
     * 邮件不存在时数据库侧直接给出 null 流，不再查询正文。
     */
    private val resolvedBody: Flow<BodyState?> =
        mailFlow.flatMapLatest { mail ->
            if (mail == null) {
                flowOf<BodyState?>(null)
            } else {
                // cachedBody 是挂起函数（不是 Flow），必须先用 flow {} 包一层，
                // 否则 .map 会被解析成 CharSequence.map 而引发类型错误。
                flow { emit(container.emails.cachedBody(emailId)) }
                    .map { cached ->
                        cached?.takeIf { it.isNotEmpty() }?.let { BodyState.Loaded(it) }
                    }
            }
        }.combine(bodyState) { cached, inMemory ->
            // 优先级：内存加载结果（成功/加载中/失败）> 数据库缓存 > 未开始
            when {
                inMemory is BodyState.Loaded || inMemory is BodyState.Loading -> inMemory
                inMemory is BodyState.Failed -> inMemory
                cached != null -> cached
                else -> BodyState.Idle
            }
        }

    val uiState: StateFlow<DetailUiState> = combine(
        mailFlow,
        resolvedBody,
        deleted,
        mailObserved,
    ) { mail, body, isDeleted, observed ->
        DetailUiState(
            mail = mail,
            body = (body as? BodyState.Loaded)?.text,
            loadingBody = body is BodyState.Loading,
            loadingMail = !observed,
            error = (body as? BodyState.Failed)?.message,
            deleted = isDeleted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = DetailUiState(),
    )

    /**
     * 加载正文。
     *
     * 幂等：已在加载中或已加载成功时直接返回，避免重复网络请求。
     */
    fun loadBody() {
        // 先读一次本地缓存，避免「已缓存但仍发请求」
        if (bodyState.value is BodyState.Loading || bodyState.value is BodyState.Loaded) return

        viewModelScope.launch {
            bodyState.value = BodyState.Loading
            val cached = runCatching { container.emails.cachedBody(emailId) }.getOrNull()
            if (!cached.isNullOrEmpty()) {
                bodyState.value = BodyState.Loaded(cached)
                return@launch
            }

            container.sync.loadBody(emailId)
                .onSuccess { text -> bodyState.value = BodyState.Loaded(text) }
                .onFailure { throwable ->
                    bodyState.value = BodyState.Failed(
                        throwable.toMailError("正文加载失败").userMessage,
                    )
                    Logs.w(TAG, "正文加载失败 emailId=$emailId type=${throwable.javaClass.simpleName}")
                }
        }
    }

    /**
     * 正文加载失败后重试：重置失败状态后重新走一次 [loadBody]。
     */
    fun retryLoadBody() {
        bodyState.value = BodyState.Idle
        loadBody()
    }

    /** 标记已读/未读 */
    fun markRead(read: Boolean) {
        viewModelScope.launch {
            container.sync.setRead(emailId, read)
                .onFailure { Logs.w(TAG, "标记已读失败 emailId=$emailId") }
        }
    }

    /** 标星 / 取消星标 */
    fun setFlagged(flagged: Boolean) {
        viewModelScope.launch {
            container.sync.setFlagged(emailId, flagged)
                .onFailure { Logs.w(TAG, "标星失败 emailId=$emailId") }
        }
    }

    /** 删除邮件；成功后 [DetailUiState.deleted] 置位，屏幕层自动返回 */
    fun delete() {
        viewModelScope.launch {
            container.sync.deleteEmail(emailId)
                .onSuccess { deleted.value = true }
                .onFailure { throwable ->
                    Logs.w(TAG, "删除失败 emailId=$emailId type=${throwable.javaClass.simpleName}")
                }
        }
    }

    companion object {
        private const val TAG = "EmailDetailViewModel"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(
            container: AppContainer,
            emailId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { EmailDetailViewModel(container, emailId) }
        }
    }
}
