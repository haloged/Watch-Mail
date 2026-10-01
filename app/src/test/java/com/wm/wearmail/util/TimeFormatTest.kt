package com.wm.wearmail.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间智能格式化的单元测试。
 *
 * 固定「当前时间」为 2025-03-12（周三）15:30，时区为 Asia/Shanghai，
 * 因此断言完全确定，不受运行时环境影响。
 */
class TimeFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Long = millisOf(2025, 3, 12, 15, 30)

    private fun millisOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    // ---------------- 今天 ----------------

    @Test
    fun `今天的邮件显示时分`() {
        assertEquals("09:05", TimeFormat.smartTime(millisOf(2025, 3, 12, 9, 5), now, zone))
        assertEquals("15:29", TimeFormat.smartTime(millisOf(2025, 3, 12, 15, 29), now, zone))
        assertEquals("00:00", TimeFormat.smartTime(millisOf(2025, 3, 12, 0, 0), now, zone))
    }

    @Test
    fun `同一天但时间在未来仍按今天处理`() {
        // 设备时钟偏差不应导致显示成「周X」或日期
        assertEquals("15:45", TimeFormat.smartTime(millisOf(2025, 3, 12, 15, 45), now, zone))
    }

    // ---------------- 本周 ----------------

    @Test
    fun `本周内显示中文星期`() {
        // 2025-03-10 是周一，与 03-12 同属一个 ISO 周
        assertEquals("周一", TimeFormat.smartTime(millisOf(2025, 3, 10, 8, 0), now, zone))
        assertEquals("周二", TimeFormat.smartTime(millisOf(2025, 3, 11, 8, 0), now, zone))
        // 同周的周日 2025-03-16 也应显示星期
        assertEquals("周日", TimeFormat.smartTime(millisOf(2025, 3, 16, 23, 59), now, zone))
    }

    @Test
    fun `上周的邮件显示日期而非星期`() {
        // 2025-03-09 是上周日
        assertEquals("03/09", TimeFormat.smartTime(millisOf(2025, 3, 9, 20, 0), now, zone))
    }

    // ---------------- 更早 ----------------

    @Test
    fun `同年更早的邮件显示月日`() {
        assertEquals("03/01", TimeFormat.smartTime(millisOf(2025, 3, 1, 12, 0), now, zone))
        assertEquals("01/01", TimeFormat.smartTime(millisOf(2025, 1, 1, 12, 0), now, zone))
    }

    @Test
    fun `跨年的邮件显示完整日期`() {
        assertEquals("2024/12/31", TimeFormat.smartTime(millisOf(2024, 12, 31, 23, 0), now, zone))
        assertEquals("2023/06/15", TimeFormat.smartTime(millisOf(2023, 6, 15, 10, 0), now, zone))
    }

    @Test
    fun `非法时间戳返回空串`() {
        assertEquals("", TimeFormat.smartTime(0L, now, zone))
        assertEquals("", TimeFormat.smartTime(-1L, now, zone))
    }

    // ---------------- 完整时间 / 相对时间 ----------------

    @Test
    fun `完整时间格式为 年-月-日 时_分`() {
        assertEquals("2025/03/12 09:05", TimeFormat.fullTime(millisOf(2025, 3, 12, 9, 5), zone))
    }

    @Test
    fun `相对时间按区间给出中文描述`() {
        assertEquals("刚刚", TimeFormat.relativeTime(now - 30_000L, now))
        assertEquals("5 分钟前", TimeFormat.relativeTime(now - 5L * 60_000L, now))
        assertEquals("3 小时前", TimeFormat.relativeTime(now - 3L * 3_600_000L, now))
        assertEquals("2 天前", TimeFormat.relativeTime(now - 2L * 86_400_000L, now))
        assertEquals("从未", TimeFormat.relativeTime(0L, now))
    }

    // ---------------- ISO 周判定 ----------------

    @Test
    fun `ISO 周判定可跨年`() {
        // 2024-12-30（周一）与 2025-01-01（周三）属于同一 ISO 周（2025 年第 1 周）
        assertTrue(
            TimeFormat.isSameIsoWeek(
                LocalDate.of(2024, 12, 30),
                LocalDate.of(2025, 1, 1),
            ),
        )
        assertFalse(
            TimeFormat.isSameIsoWeek(
                LocalDate.of(2025, 1, 5),
                LocalDate.of(2025, 1, 6),
            ),
        )
    }

    @Test
    fun `星期名称覆盖周一到周日`() {
        assertEquals("周一", TimeFormat.weekDayName(java.time.DayOfWeek.MONDAY))
        assertEquals("周日", TimeFormat.weekDayName(java.time.DayOfWeek.SUNDAY))
    }
}
