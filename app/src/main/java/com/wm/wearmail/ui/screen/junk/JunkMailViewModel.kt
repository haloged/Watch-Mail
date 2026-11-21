package com.wm.wearmail.ui.screen.junk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.data.db.EmailDao
import com.wm.wearmail.mail.isJunkFolder
import com.wm.wearmail.mail.toEmailMeta
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.EmailMeta
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 垃圾邮件箱：按需拉取**所有账户**的垃圾邮件文件夹并合并展示。
 *
 * 设计取舍：
 * - **不进同步主链路**：垃圾邮件不参与 IDLE/增量同步，也不触发通知（避免把钓鱼内容
 *   当成正常邮件推送）；只在用户主动打开本页时拉最近 [JUNK_LIMIT] 封；
 * - **入库仅为详情页复用**：拉到的元数据写入本地缓存（folder 为真实垃圾文件夹名），
 *   收件箱查询固定过滤 `folder = 'INBOX'`，因此**不会混入统一收件箱**；
 * - 各账户并发拉取，单个账户失败不影响其他账户（只汇总失败数）。
 */
class JunkMailViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val mails: List<EmailMeta> = emptyList(),
        /** 账户列表（给邮件行取 colorIndex 用） */
        val accounts: List<Account> = emptyList(),
        /** 拉取失败（或没有垃圾文件夹）的账户数 */
        val failedAccounts: Int = 0,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val accounts = runCatching { container.accounts.load() }.getOrDefault(emptyList())
            if (accounts.isEmpty()) {
                _uiState.update {
                    it.copy(loading = false, mails = emptyList(), accounts = emptyList(), failedAccounts = 0)
                }
                return@launch
            }
            val results = coroutineScope {
                accounts.map { account -> async { runCatching { fetchJunk(account) }.getOrNull() } }.awaitAll()
            }
            val failed = results.count { it == null }
            val merged = results.filterNotNull().flatten()
                .sortedByDescending { it.dateMillis }
                .distinctBy { it.accountId to it.uid }
            _uiState.update {
                it.copy(
                    loading = false,
                    mails = merged,
                    accounts = accounts,
                    failedAccounts = failed,
                    error = if (merged.isEmpty() && failed == accounts.size) {
                        "拉取失败：请检查网络或账户凭据"
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** 拉取单个账户的垃圾邮件（可能同时有多个垃圾文件夹） */
    private suspend fun fetchJunk(account: Account): List<EmailMeta> {
        val secrets = container.oauthTokens.freshSecrets(account).getOrElse {
            Logs.w(TAG, "账户 ${account.id} 凭据不可用，跳过")
            return emptyList()
        }
        val folders = container.imap.listFolders(account, secrets).getOrElse {
            Logs.w(TAG, "账户 ${account.id} 列文件夹失败")
            return emptyList()
        }
        val junkFolders = folders.filter { it.isJunkFolder() }
        if (junkFolders.isEmpty()) return emptyList()

        val dao = EmailDao(container.db)
        val result = mutableListOf<EmailMeta>()
        for (folder in junkFolders) {
            val remotes = container.imap.fetchRecent(account, secrets, folder.name, JUNK_LIMIT)
                .getOrNull()
            if (remotes == null) {
                Logs.w(TAG, "账户 ${account.id} 拉取垃圾文件夹失败：${folder.name}")
                continue
            }
            if (remotes.isEmpty()) continue
            dao.upsertAll(remotes.map { it.toEmailMeta(account.id, folder.name) })
            // 入库后回查一次，拿到本地主键（详情页按 id 打开）
            result += dao.queryByFolder(account.id, folder.name, JUNK_LIMIT)
        }
        return result
    }

    companion object {
        private const val TAG = "JunkMail"
        private const val JUNK_LIMIT = 15

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { JunkMailViewModel(container) }
        }
    }
}
