package com.wm.wearmail.model

/**
 * 同步触发原因，用于日志标注与节流策略区分。
 */
enum class SyncReason(val label: String) {
    /** 用户下拉刷新 */
    MANUAL("手动刷新"),

    /** 前台定时同步（默认 5 分钟） */
    FOREGROUND("前台定时"),

    /** 后台 WorkManager 同步（15~30 分钟） */
    BACKGROUND("后台定时"),

    /** IMAP IDLE 推送唤醒 */
    IDLE("推送唤醒"),

    /** 应用冷启动后的首次同步 */
    STARTUP("启动同步"),
}

/**
 * 单账户同步结果。
 *
 * @param newMailCount 本次新入库的邮件数量
 * @param durationMillis 耗时，用于性能核对（目标：单次同步 < 5s）
 */
data class AccountSyncResult(
    val accountId: Long,
    val success: Boolean,
    val newMailCount: Int = 0,
    val durationMillis: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * 一次「同步全部账户」的汇总结果。
 */
data class SyncReport(
    val reason: SyncReason,
    val results: List<AccountSyncResult>,
    val startedAt: Long,
    val finishedAt: Long,
) {
    val newMailCount: Int
        get() = results.sumOf { it.newMailCount }

    val successCount: Int
        get() = results.count { it.success }

    val failedCount: Int
        get() = results.count { !it.success }

    val durationMillis: Long
        get() = finishedAt - startedAt

    val allFailed: Boolean
        get() = results.isNotEmpty() && successCount == 0

    companion object {
        /** 空报告：账户列表为空时使用 */
        fun empty(reason: SyncReason): SyncReport {
            val now = System.currentTimeMillis()
            return SyncReport(reason = reason, results = emptyList(), startedAt = now, finishedAt = now)
        }
    }
}

/**
 * 同步状态（供 UI 观察）。
 */
data class SyncUiState(
    /** 是否正在同步 */
    val running: Boolean = false,
    val reason: SyncReason? = null,
    /** 最近一次成功同步时间 */
    val lastSuccessAt: Long = 0L,
    /** 最近一次失败原因（成功时清空） */
    val lastError: String? = null,
    /** 本次同步新增邮件数，用于顶部提示「收到 N 封新邮件」 */
    val newMailCount: Int = 0,
) {
    val hasError: Boolean
        get() = lastError != null
}

/**
 * 发送结果（撰写页状态机使用）。
 */
sealed interface SendState {
    /** 待发送/空闲 */
    data object Idle : SendState

    /** 发送中（UI 展示转圈动画） */
    data object Sending : SendState

    /** 发送成功（UI 展示 ✓ + 振动） */
    data object Success : SendState

    /** 发送失败（UI 展示 ✗ + 振动 + 重试按钮），草稿已落盘 */
    data class Failure(val message: String, val draftId: Long? = null) : SendState
}
