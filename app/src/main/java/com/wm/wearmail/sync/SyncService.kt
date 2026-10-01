package com.wm.wearmail.sync

import com.wm.wearmail.model.AccountSyncResult
import com.wm.wearmail.model.SyncReason
import com.wm.wearmail.model.SyncReport
import com.wm.wearmail.model.SyncUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 同步与邮件动作门面。
 *
 * 这是 UI 层与网络层之间的唯一入口：所有涉及网络的邮件操作
 * （同步、拉正文、改标记、删除、发送）都经由本接口，
 * 从而保证：
 * - 统一的连接超时与指数退避重连策略；
 * - 统一的错误提示（[Result] 中承载 [com.wm.wearmail.mail.MailError]）；
 * - 统一的缓存与 LRU 淘汰触发时机。
 */
interface SyncService {

    /** 同步状态（UI 顶部提示 / 转圈动画使用） */
    val state: StateFlow<SyncUiState>

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动前台定时同步循环（默认 5 分钟一次）。
     * 由 MainActivity 在 `onStart` 时调用，`onStop` 时调用 [stopForegroundLoop]。
     */
    fun startForegroundLoop(scope: CoroutineScope)

    /** 停止前台定时同步循环 */
    fun stopForegroundLoop()

    /** 注册后台周期同步（WorkManager，15~30 分钟，遵循系统功耗限制） */
    fun scheduleBackgroundSync()

    /** 取消后台周期同步（用户选择「仅手动刷新」时调用） */
    fun cancelBackgroundSync()

    // ------------------------------------------------------------------
    // 同步动作
    // ------------------------------------------------------------------

    /** 同步全部账户（下拉刷新 / 定时触发） */
    suspend fun syncAll(reason: SyncReason): SyncReport

    /** 同步单个账户 */
    suspend fun syncAccount(accountId: Long, reason: SyncReason): AccountSyncResult

    // ------------------------------------------------------------------
    // 邮件动作
    // ------------------------------------------------------------------

    /**
     * 加载正文：优先返回本地缓存；未命中时按需下载并写入缓存。
     */
    suspend fun loadBody(emailId: Long): Result<String>

    /** 标记已读/未读（先改本地再同步远端，远端失败不回滚本地） */
    suspend fun setRead(emailId: Long, read: Boolean): Result<Unit>

    /** 标记/取消星标 */
    suspend fun setFlagged(emailId: Long, flagged: Boolean): Result<Unit>

    /** 删除邮件（远端删除成功后删除本地记录） */
    suspend fun deleteEmail(emailId: Long): Result<Unit>

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /**
     * 直接发送一封邮件。
     *
     * 失败时**自动保存为草稿**并返回失败原因，保证内容不丢失。
     */
    suspend fun send(
        accountId: Long,
        to: String,
        subject: String,
        body: String,
    ): Result<Unit>

    /** 投递指定草稿（撰稿页「重试」按钮） */
    suspend fun sendDraft(draftId: Long): Result<Unit>

    /**
     * 尝试投递全部待发送草稿（联网后自动调用）。
     * @return 成功投递的草稿数量
     */
    suspend fun flushPendingDrafts(): Int
}
