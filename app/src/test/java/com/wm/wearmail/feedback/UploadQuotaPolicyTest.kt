package com.wm.wearmail.feedback

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传日志风控策略的单元测试（纯函数）。
 *
 * 规则：1 分钟内只能上传一次；每天最多 5 次（本地时区自然日重置）。
 * 守护点：间隔边界、日次数上限、跨天重置、时钟回拨不卡死用户、剩余次数。
 */
class UploadQuotaPolicyTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-10-30 12:00:00 UTC */
    private val noon = 1_793_361_600_000L

    private fun state(day: String = "2026-10-30", count: Int = 0, lastAt: Long = 0L) =
        UploadQuotaState(dayKey = day, attemptsToday = count, lastAttemptAtMillis = lastAt)

    @Test
    fun `首次上传允许`() {
        assertEquals(
            UploadDecision.Allowed,
            UploadQuotaPolicy.evaluate(state(), noon, "2026-10-30"),
        )
    }

    @Test
    fun `一分钟内拒绝并给出剩余等待时间`() {
        val decision = UploadQuotaPolicy.evaluate(
            state(count = 1, lastAt = noon - 20_000L),
            noon,
            "2026-10-30",
        )
        assertTrue(decision is UploadDecision.TooSoon)
        assertEquals(40_000L, (decision as UploadDecision.TooSoon).remainMillis)
    }

    @Test
    fun `恰好满一分钟允许`() {
        assertEquals(
            UploadDecision.Allowed,
            UploadQuotaPolicy.evaluate(
                state(count = 1, lastAt = noon - UploadQuotaPolicy.MIN_INTERVAL_MILLIS),
                noon,
                "2026-10-30",
            ),
        )
    }

    @Test
    fun `当天达到五次后拒绝`() {
        val full = state(count = UploadQuotaPolicy.MAX_UPLOADS_PER_DAY, lastAt = noon - 120_000L)
        assertEquals(UploadDecision.DailyLimit, UploadQuotaPolicy.evaluate(full, noon, "2026-10-30"))
        assertEquals(0, UploadQuotaPolicy.remainingToday(full, noon, "2026-10-30"))
    }

    @Test
    fun `跨天重置每日次数但一分钟间隔仍生效`() {
        // 昨天用满 5 次，且距上次已超过 1 分钟：跨天后计数重置
        val yesterday = state(day = "2026-10-29", count = 5, lastAt = noon - 120_000L)
        assertEquals(UploadDecision.Allowed, UploadQuotaPolicy.evaluate(yesterday, noon, "2026-10-30"))
        assertEquals(5, UploadQuotaPolicy.remainingToday(yesterday, noon, "2026-10-30"))

        val registered = UploadQuotaPolicy.register(yesterday, noon, "2026-10-30")
        assertEquals("2026-10-30", registered.dayKey)
        assertEquals(1, registered.attemptsToday)

        // 但若跨零点前 30 秒才试过：仍要等满 1 分钟（防止卡零点刷次数）
        val justBeforeMidnight = state(day = "2026-10-29", count = 5, lastAt = noon - 30_000L)
        assertTrue(
            UploadQuotaPolicy.evaluate(justBeforeMidnight, noon, "2026-10-30") is UploadDecision.TooSoon,
        )
    }

    @Test
    fun `同一天计数累加`() {
        val first = UploadQuotaPolicy.register(state(), noon, "2026-10-30")
        assertEquals(1, first.attemptsToday)
        val second = UploadQuotaPolicy.register(first, noon + 90_000L, "2026-10-30")
        assertEquals(2, second.attemptsToday)
        assertEquals(3, UploadQuotaPolicy.remainingToday(second, noon + 90_000L, "2026-10-30"))
    }

    @Test
    fun `时钟回拨不卡死`() {
        // 上次时间在未来（系统时间被调回）：不应判定为「太频繁」
        assertEquals(
            UploadDecision.Allowed,
            UploadQuotaPolicy.evaluate(state(count = 1, lastAt = noon + 600_000L), noon, "2026-10-30"),
        )
    }

    @Test
    fun `日期键按本地时区生成`() {
        assertEquals("2026-10-30", UploadQuotaPolicy.todayKey(noon, utc))
    }
}
