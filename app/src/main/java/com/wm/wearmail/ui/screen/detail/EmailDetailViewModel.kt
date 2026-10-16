package com.wm.wearmail.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.db.HtmlCacheState
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
 * 渲染相关设置的聚合（HTML 渲染 / 远程图片 / 链接外部打开）。
 *
 * 这三个开关只在邮件详情页消费、且总是一起变化，聚合后避免
 * 用嵌套 Pair/Triple 传递（可读性优先）。
 */
data class RenderSettings(
    val htmlEnabled: Boolean,
    val remoteImagesEnabled: Boolean,
    val openLinksInBrowser: Boolean,
)

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
    /** 原始 HTML 正文（仅在设置开启「渲染 HTML 邮件」后才会缓存/加载） */
    val html: String? = null,
    /** 设置：是否渲染 HTML（默认开启，见 AppSettings.htmlRenderingEnabled） */
    val htmlEnabled: Boolean = false,
    /** 设置：HTML 渲染时是否允许加载远程图片 */
    val remoteImagesEnabled: Boolean = false,
    /** 设置：点击邮件内链接是否用浏览器打开（默认关闭，见 AppSettings.openLinksInBrowser） */
    val openLinksInBrowser: Boolean = false,
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

    /**
     * 本次会话中拿到的原始 HTML（来自缓存或刚刚下载）。
     *
     * 单独保存而不是塞进 [BodyState]：HTML 体积可能是纯文本的几十倍，
     * 只有真的要渲染时才需要它。
     */
    private val htmlState = MutableStateFlow<String?>(null)

    /**
     * 缓存里的 HTML 是否已有结论（[HtmlCacheState.AVAILABLE] 或 [HtmlCacheState.NONE]）。
     *
     * [loadBody] 的重载判定用它代替「数据库里 HTML 非空」：NONE 表示问过服务器、
     * 这封邮件确实没有可渲染的 HTML，不能反复触发重新下载（否则纯文本邮件永远
     * 无法离线打开，且每次都白跑一次网络）。
     */
    private var htmlCacheResolved = false

    /** 渲染相关设置（HTML 渲染 / 远程图片 / 链接外部打开），随设置页改动即时生效 */
    private val renderSettings: Flow<RenderSettings> = container.settings.settings
        .map {
            RenderSettings(
                htmlEnabled = it.htmlRenderingEnabled,
                remoteImagesEnabled = it.remoteImagesEnabled,
                openLinksInBrowser = it.openLinksInBrowser,
            )
        }

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

    /**
     * 数据库里缓存的原始 HTML。
     *
     * 只在确实有邮件时查询；单独一条 [Flow] 是因为 HTML 可能几百 KB，
     * 纯文本模式（默认）下不应把它读进内存。
     */
    private val cachedHtmlFlow: Flow<String?> = mailFlow.flatMapLatest { mail ->
        if (mail == null) {
            flowOf<String?>(null)
        } else {
            flow { emit(container.emails.cachedHtml(emailId)) }
        }
    }

    /** HTML 来源优先级：本次会话刚拿到的（内存）> 数据库缓存 */
    private val resolvedHtml: Flow<String?> = combine(cachedHtmlFlow, htmlState) { cached, inMemory ->
        inMemory ?: cached
    }

    /**
     * 正文 + HTML + 渲染设置的合并流。
     *
     * 单独合并的原因：[kotlinx.coroutines.flow.combine] 的类型化重载最多 5 个流，
     * 而最终 uiState 已经用了 4 个，这里先把三个相关状态压成一个。
     */
    private val bodyAndRendering: Flow<Triple<BodyState?, String?, RenderSettings>> =
        combine(resolvedBody, resolvedHtml, renderSettings) { body, html, rendering ->
            Triple(body, html, rendering)
        }

    val uiState: StateFlow<DetailUiState> = combine(
        mailFlow,
        bodyAndRendering,
        deleted,
        mailObserved,
    ) { mail, (body, html, rendering), isDeleted, observed ->
        DetailUiState(
            mail = mail,
            body = (body as? BodyState.Loaded)?.text,
            html = html,
            htmlEnabled = rendering.htmlEnabled,
            remoteImagesEnabled = rendering.remoteImagesEnabled,
            openLinksInBrowser = rendering.openLinksInBrowser,
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
     * 加载正文（含按需的 HTML）。
     *
     * 幂等：已在加载中直接返回。特殊情况是**用户中途开启了 HTML 渲染**：
     * 此时内存里虽有纯文本，但 HTML 还没有结论，必须再取一次（[SyncService.loadBody] 会因
     * 「HTML 缓存尚无结论」而重新下载），否则开关看起来没生效。
     */
    fun loadBody() {
        if (bodyState.value is BodyState.Loading) return

        val wantHtml = runCatching {
            container.settings.settings.value.htmlRenderingEnabled
        }.getOrDefault(false)

        val alreadyLoaded = bodyState.value is BodyState.Loaded
        // 已加载：只在「要 HTML，但本次会话还没有 HTML、缓存也没有结论」时才重新走一遍
        if (alreadyLoaded && (!wantHtml || htmlState.value != null || htmlCacheResolved)) return

        viewModelScope.launch {
            bodyState.value = BodyState.Loading

            val cached = runCatching { container.emails.cachedBody(emailId) }.getOrNull()
            readCachedHtml()

            // 「缓存完整」的判定用 HTML 三态：UNKNOWN（没问过）才需要补取；
            // NONE（问过但没有）说明这封邮件本来就没有可渲染的 HTML，不能反复重下。
            val htmlReady = !wantHtml || htmlState.value != null || htmlCacheResolved
            if (!cached.isNullOrEmpty() && htmlReady) {
                bodyState.value = BodyState.Loaded(cached)
                return@launch
            }

            container.sync.loadBody(emailId)
                .onSuccess { text ->
                    bodyState.value = BodyState.Loaded(text)
                    // 同步刚刚写入缓存，这里读回 HTML 与三态结论（未开启 / 无 HTML 时为 null）
                    readCachedHtml()
                }
                .onFailure { throwable ->
                    bodyState.value = BodyState.Failed(
                        throwable.toMailError("正文加载失败").userMessage,
                    )
                    Logs.w(TAG, "正文加载失败 emailId=$emailId type=${throwable.javaClass.simpleName}")
                }
        }
    }

    /**
     * 从数据库读回 HTML 与三态结论，更新 [htmlState] 与 [htmlCacheResolved]。
     *
     * 「缓存命中」与「同步刚写完缓存」两处都需要同一份读回逻辑，故提取为私有方法。
     */
    private suspend fun readCachedHtml() {
        htmlState.value = runCatching { container.emails.cachedHtml(emailId) }.getOrNull()
        val state = runCatching { container.emails.cachedHtmlState(emailId) }.getOrNull()
        if (state?.resolved == true) htmlCacheResolved = true
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
