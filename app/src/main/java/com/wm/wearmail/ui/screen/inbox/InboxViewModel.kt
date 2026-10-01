package com.wm.wearmail.ui.screen.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.mail.toMailError
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.EmailMeta
import com.wm.wearmail.model.SyncReason
import com.wm.wearmail.model.SyncReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 统一收件箱状态。
 *
 * @param accounts 已绑定账户（筛选项来源）
 * @param selectedAccountId null 表示「全部账户」
 * @param mails 当前筛选下的邮件，按时间倒序
 * @param unreadCounts 各账户未读数（账户 id → 数量）
 * @param syncing 是否正在同步（下拉刷新指示器使用）
 * @param lastSuccessAt 最近一次同步成功时间（0 表示从未）
 * @param error 面向用户的错误提示；null 表示无错误
 */
data class InboxUiState(
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: Long? = null,
    val mails: List<EmailMeta> = emptyList(),
    val unreadCounts: Map<Long, Int> = emptyMap(),
    val syncing: Boolean = false,
    val lastSuccessAt: Long = 0L,
    val error: String? = null,
)

/**
 * 统一收件箱 ViewModel。
 *
 * 数据流设计：
 * - [selectedAccountId] 变化时通过 [flatMapLatest] **重新订阅** `observeInbox` 与
 *   各账户未读数流，保证切换账户筛选后不残留旧账户的订阅；
 * - 其余状态（账户列表、同步状态、手动刷新错误）用 `combine` 汇聚，
 *   最终 `stateIn` 成单一 [StateFlow]，屏幕层只需一次 `collectAsStateWithLifecycle`。
 *
 * 安全约定：日志中只输出错误类型等非敏感信息，
 * **绝不**输出邮件正文、密码或 OAuth 令牌。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(private val container: AppContainer) : ViewModel() {

    /** 当前筛选的账户；null = 全部 */
    private val selectedAccountId = MutableStateFlow<Long?>(null)

    /** 手动刷新/动作失败产生的错误，与 sync.state.lastError 合并展示 */
    private val actionError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<InboxUiState> = selectedAccountId
        .flatMapLatest { accountId ->
            // 邮件列表：账户筛选变化时重新订阅
            val mailsFlow = container.emails.observeInbox(accountId)
            // 未读数：每个账户一个流，合并成 id → count 的映射
            val unreadCountsFlow = observeUnreadCounts()

            // combine：邮件列表、未读数、账户列表、同步状态、手动动作错误
            combine(
                mailsFlow,
                unreadCountsFlow,
                container.accounts.accounts,
                container.sync.state,
                actionError,
            ) { mails, unreadCounts, accounts, sync, error ->
                InboxUiState(
                    accounts = accounts,
                    selectedAccountId = accountId,
                    mails = mails,
                    unreadCounts = unreadCounts,
                    syncing = sync.running,
                    lastSuccessAt = sync.lastSuccessAt,
                    // 手动动作错误优先（更贴近用户刚刚的操作），其次同步错误
                    error = error ?: sync.lastError,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = InboxUiState(syncing = container.sync.state.value.running),
        )

    /**
     * 各账户未读数合并流。
     *
     * 账户列表本身是热流，切换账户增删后会自动重订阅（`flatMapLatest` + `combine`），
     * 因此不需要手写去抖或手动刷新。
     */
    private fun observeUnreadCounts(): Flow<Map<Long, Int>> =
        container.accounts.accounts.flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                // combine 一个空的 Flow 列表不会发射任何值，这里必须显式给出空映射
                MutableStateFlow(emptyMap<Long, Int>())
            } else {
                val flows: List<Flow<Pair<Long, Int>>> = accounts.map { account ->
                    // 单个 Flow 用 map 而不是 combine：combine(flow){} 的单流重载在
                    // 这里会被解析成 vararg 版本，导致 lambda 参数变成 Array<Int> 而类型不符。
                    container.emails.observeUnreadCount(account.id)
                        .map { count -> account.id to count }
                }
                combine(flows) { pairs -> pairs.toMap() }
            }
        }

    /** 切换账户筛选（null = 全部账户） */
    fun selectAccount(id: Long?) {
        if (selectedAccountId.value == id) return
        selectedAccountId.value = id
    }

    /** 手动刷新（下拉刷新 / 错误重试） */
    fun refresh() {
        viewModelScope.launch {
            try {
                actionError.value = container.sync.syncAll(SyncReason.MANUAL).toUserMessage()
            } catch (t: Throwable) {
                actionError.value = t.toMailError("同步失败").userMessage
                Logs.w(TAG, "手动同步异常：${t.javaClass.simpleName}")
            }
        }
    }

    /** 标记已读/未读 */
    fun markRead(id: Long, read: Boolean) {
        viewModelScope.launch {
            container.sync.setRead(id, read)
                .onFailure { actionError.value = it.toMailError("标记已读失败").userMessage }
        }
    }

    /** 标星 / 取消星标 */
    fun setFlagged(id: Long, flagged: Boolean) {
        viewModelScope.launch {
            container.sync.setFlagged(id, flagged)
                .onFailure { actionError.value = it.toMailError("标星失败").userMessage }
        }
    }

    /** 删除邮件（远端删除成功后本地记录消失，列表自动刷新） */
    fun delete(id: Long) {
        viewModelScope.launch {
            container.sync.deleteEmail(id)
                .onFailure { actionError.value = it.toMailError("删除失败").userMessage }
        }
    }

    /** 用户点掉错误提示 */
    fun clearError() {
        actionError.value = null
    }

    companion object {
        private const val TAG = "InboxViewModel"

        /** 界面不可见后仍保留订阅 5 秒，避免切换页面时反复重建数据库查询 */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { InboxViewModel(container) }
        }
    }
}

/**
 * 把一次同步汇总转成面向用户的错误提示：全部成功返回 null。
 *
 * 只取协议层给出的 [com.wm.wearmail.model.AccountSyncResult.errorMessage]，
 * 不做任何邮件内容相关的输出。
 */
private fun SyncReport.toUserMessage(): String? {
    val failures = results.filter { !it.success }
    if (failures.isEmpty()) return null
    val detail = failures.firstNotNullOfOrNull { it.errorMessage?.takeIf { text -> text.isNotBlank() } }
    return detail ?: "${failures.size} 个账户同步失败，请稍后重试"
}
