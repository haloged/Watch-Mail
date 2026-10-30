package com.wm.wearmail.feedback

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 上传日志的风控判定结果 */
sealed interface UploadDecision {

    /** 允许上传 */
    data object Allowed : UploadDecision

    /** 距上次上传不足 1 分钟 */
    data class TooSoon(val remainMillis: Long) : UploadDecision

    /** 今天的次数已用完 */
    data object DailyLimit : UploadDecision
}

/** 上传风控状态（只存必要字段，便于序列化到 SharedPreferences） */
data class UploadQuotaState(
    /** 计数所属日期 `yyyy-MM-dd`（空串表示从未上传） */
    val dayKey: String = "",
    val attemptsToday: Int = 0,
    /** 上次尝试时间（毫秒），0 表示从未 */
    val lastAttemptAtMillis: Long = 0L,
)

/**
 * 上传日志的风控策略（纯函数，可单元测试）。
 *
 * 规则（产品要求）：
 * - **1 分钟内只能上传一次**（含失败重试——防止连点狂刷接口）；
 * - **每天最多 5 次**（按本地时区的自然日重置）。
 *
 * 说明：这里按「尝试次数」计数（不是「成功次数」）——风控的目的是限制对反馈表的请求量；
 * 界面上会显示「今天还可上传 N 次」，让用户在重试前知道成本。
 */
object UploadQuotaPolicy {

    /** 两次上传之间的最小间隔 */
    const val MIN_INTERVAL_MILLIS: Long = 60_000L

    /** 每天的尝试次数上限 */
    const val MAX_UPLOADS_PER_DAY: Int = 5

    /** 当天日期键（本地时区） */
    fun todayKey(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(nowMillis))

    /** 判定当前是否允许上传 */
    fun evaluate(
        state: UploadQuotaState,
        nowMillis: Long,
        todayKey: String = todayKey(nowMillis),
    ): UploadDecision {
        val attemptsToday = if (state.dayKey == todayKey) state.attemptsToday else 0
        if (attemptsToday >= MAX_UPLOADS_PER_DAY) return UploadDecision.DailyLimit
        if (state.lastAttemptAtMillis > 0L) {
            val elapsed = nowMillis - state.lastAttemptAtMillis
            // elapsed 为负说明时钟回拨：按「允许」处理，不因系统时间异常卡死用户
            if (elapsed in 0 until MIN_INTERVAL_MILLIS) {
                return UploadDecision.TooSoon(MIN_INTERVAL_MILLIS - elapsed)
            }
        }
        return UploadDecision.Allowed
    }

    /** 记录一次尝试（跨天自动重置计数） */
    fun register(
        state: UploadQuotaState,
        nowMillis: Long,
        todayKey: String = todayKey(nowMillis),
    ): UploadQuotaState {
        val count = if (state.dayKey == todayKey) state.attemptsToday + 1 else 1
        return UploadQuotaState(
            dayKey = todayKey,
            attemptsToday = count,
            lastAttemptAtMillis = nowMillis,
        )
    }

    /** 今天剩余次数 */
    fun remainingToday(
        state: UploadQuotaState,
        nowMillis: Long,
        todayKey: String = todayKey(nowMillis),
    ): Int {
        val used = if (state.dayKey == todayKey) state.attemptsToday else 0
        return (MAX_UPLOADS_PER_DAY - used).coerceAtLeast(0)
    }
}

/**
 * 风控状态的持久化（SharedPreferences）。
 *
 * 与 [UploadQuotaPolicy] 分离的原因：策略是纯函数（可测），这里只负责读写。
 * 未选 SettingsStore 是因为它属于「用户设置」，而这是短期的限流状态。
 */
object FeedbackQuota {

    private const val PREFS_NAME = "feedback_quota"
    private const val KEY_DAY = "day"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_AT = "last_at"

    fun currentState(context: Context): UploadQuotaState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UploadQuotaState(
            dayKey = prefs.getString(KEY_DAY, "").orEmpty(),
            attemptsToday = prefs.getInt(KEY_COUNT, 0),
            lastAttemptAtMillis = prefs.getLong(KEY_LAST_AT, 0L),
        )
    }

    fun evaluate(context: Context, nowMillis: Long = System.currentTimeMillis()): UploadDecision =
        UploadQuotaPolicy.evaluate(currentState(context), nowMillis)

    /** 记录一次尝试（**在发起请求前调用**：失败重试同样计数） */
    fun recordAttempt(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val next = UploadQuotaPolicy.register(currentState(context), nowMillis)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DAY, next.dayKey)
            .putInt(KEY_COUNT, next.attemptsToday)
            .putLong(KEY_LAST_AT, next.lastAttemptAtMillis)
            .apply()
    }

    /** 今天剩余次数（界面展示 + 按钮禁用判断） */
    fun remainingToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int =
        UploadQuotaPolicy.remainingToday(currentState(context), nowMillis)
}
