package com.wm.wearmail.ui.screen.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 账户列表中的一行。
 *
 * 未读数从 [com.wm.wearmail.data.repo.EmailRepository.observeUnreadCount] 实时聚合，
 * 与账户对象一起打包，避免 UI 层做二次查找。
 */
data class AccountListRow(
    val account: Account,
    val unreadCount: Int,
)

/** 账户列表页 UI 状态 */
data class AccountListUiState(
    val loading: Boolean = true,
    val rows: List<AccountListRow> = emptyList(),
    val error: String? = null,
) {
    /** 加载完成且没有任何账户 */
    val isEmpty: Boolean
        get() = !loading && rows.isEmpty()
}

/**
 * 账户列表页的一次性事件（振动反馈 + 顶部提示文案）。
 *
 * 用事件流而不是状态，是因为「重命名成功」这类提示只应提示一次，
 * 若放进 StateFlow 会在旋转/重组后重复弹出。
 */
sealed interface AccountListEvent {
    data class Message(val text: String, val success: Boolean) : AccountListEvent
}

/**
 * 账户列表页 ViewModel。
 *
 * 职责：
 * - 把 `accounts`（内存快照流）与每个账户的未读数流合并成一个 UI 状态；
 * - 承载重命名 / 通知开关 / 删除三个动作，并在删除时清理该账户的本地缓存。
 *
 * 安全约定：本类不接触密码。重命名调用
 * [com.wm.wearmail.data.repo.AccountRepository.update] 时 `secrets` 传 null，
 * 表示「不改动凭据」，避免把明文密码读进内存。
 */
class AccountListViewModel(private val container: AppContainer) : ViewModel() {

    private val _events = MutableSharedFlow<AccountListEvent>(extraBufferCapacity = 8)

    /** 操作结果事件（屏幕据此振动并提示） */
    val events: SharedFlow<AccountListEvent> = _events.asSharedFlow()

    val uiState: StateFlow<AccountListUiState> = container.accounts.accounts
        .flatMapLatest { accounts -> rowsFlow(accounts) }
        .catch { t ->
            Logs.e(TAG, "账户列表订阅失败：${t.javaClass.simpleName}")
            emit(AccountListUiState(loading = false, error = "账户加载失败，请稍后重试"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AccountListUiState(),
        )

    init {
        refresh()
    }

    /** 从数据库重新加载账户（冷启动/新增账户返回后调用） */
    fun refresh() {
        viewModelScope.launch {
            runCatching { container.accounts.load() }
                .onFailure { Logs.w(TAG, "账户加载失败：${it.javaClass.simpleName}") }
        }
    }

    /**
     * 重命名账户（只改别名，不动凭据）。
     *
     * @param newAlias 新别名，允许为空（清空后回退显示邮箱）
     */
    fun rename(account: Account, newAlias: String) {
        viewModelScope.launch {
            val alias = newAlias.trim()
            val ok = runCatching { container.accounts.update(account.copy(alias = alias), null) }
                .getOrDefault(false)
            if (ok) {
                Logs.i(TAG, "重命名账户成功 id=${account.id}")
                _events.tryEmit(AccountListEvent.Message("已重命名", success = true))
            } else {
                Logs.w(TAG, "重命名账户失败 id=${account.id}")
                _events.tryEmit(AccountListEvent.Message("重命名失败", success = false))
            }
        }
    }

    /**
     * 切换某账户的新邮件通知。
     *
     * 需要同时写两处：账户表里的业务字段（同步引擎判定用）与
     * [com.wm.wearmail.data.prefs.SettingsStore]（设置页读取用），
     * 两边保持一致，否则设置页与账户页会显示不同状态。
     */
    fun setNotificationsEnabled(account: Account, enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                container.accounts.setNotificationsEnabled(account.id, enabled)
                container.settings.setAccountNotifications(account.id, enabled)
            }.isSuccess
            if (ok) {
                Logs.i(TAG, "账户通知开关已更新 id=${account.id} enabled=$enabled")
                _events.tryEmit(
                    AccountListEvent.Message(
                        text = if (enabled) "已开启通知" else "已关闭通知",
                        success = true,
                    ),
                )
            } else {
                Logs.w(TAG, "账户通知开关更新失败 id=${account.id}")
                _events.tryEmit(AccountListEvent.Message("设置失败", success = false))
            }
        }
    }

    /**
     * 删除账户，并清理该账户的邮件/正文/草稿缓存。
     *
     * [com.wm.wearmail.data.repo.AccountRepository.delete] 的契约已包含缓存清理，
     * 这里再显式调用一次是幂等的「双保险」：即使某个仓储实现漏删，
     * 也不会在手表上留下明文邮件正文。
     */
    fun delete(account: Account) {
        viewModelScope.launch {
            val ok = runCatching { container.accounts.delete(account.id) }.getOrDefault(false)
            if (!ok) {
                Logs.w(TAG, "删除账户失败 id=${account.id}")
                _events.tryEmit(AccountListEvent.Message("删除失败", success = false))
                return@launch
            }
            runCatching { container.emails.deleteByAccount(account.id) }
                .onFailure { Logs.w(TAG, "清理邮件缓存失败 id=${account.id}") }
            runCatching { container.drafts.deleteByAccount(account.id) }
                .onFailure { Logs.w(TAG, "清理草稿失败 id=${account.id}") }
            Logs.i(TAG, "已删除账户及其本地缓存 id=${account.id}")
            _events.tryEmit(AccountListEvent.Message("已删除账户", success = true))
        }
    }

    /** 把账户列表与各自的未读数合并成一条状态流 */
    private fun rowsFlow(accounts: List<Account>): Flow<AccountListUiState> {
        if (accounts.isEmpty()) {
            return flowOf(AccountListUiState(loading = false, rows = emptyList()))
        }
        val unreadFlows = accounts.map { account ->
            container.emails.observeUnreadCount(account.id)
                .map { unread -> AccountListRow(account = account, unreadCount = unread) }
        }
        return combine(unreadFlows) { rows ->
            AccountListUiState(loading = false, rows = rows.toList())
        }
    }

    companion object {
        private const val TAG = "AccountList"

        /** 屏幕不可见 5 秒后停止订阅上游，省电 */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** ViewModel 工厂（屏幕侧：`viewModel(factory = AccountListViewModel.factory(container))`） */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountListViewModel(container) }
        }
    }
}
