package com.wm.wearmail.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 时间智能格式化（纯 JVM 逻辑，可单元测试）。
 *
 * 展示规则（需求 2.2）：
 * - 今天：`HH:mm`，例如 09:05
 * - 本周内（同一 ISO 周）：`周X`，例如 周一
 * - 更早（同年）：`MM/DD`，例如 03/01
 * - 跨年：`yyyy/MM/DD`，例如 2024/12/31
 *
 * 之所以把 `nowMillis` 与 `zone` 作为参数注入，是为了让单元测试完全确定，
 * 不依赖运行时的系统时间与时区。
 */
object TimeFormat {

    /** 一周的中文名称，索引 0 对应周一（[DayOfWeek.MONDAY]） */
    private val weekDayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd", Locale.getDefault())
    private val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault())

    /**
     * 列表时间展示。
     *
     * @param dateMillis 邮件时间戳（毫秒）
     * @param nowMillis 当前时间戳（毫秒），默认取系统时间；测试时显式传入
     * @param zone 时区，默认系统时区
     * @return 展示字符串；[dateMillis] 非法（<= 0）时返回空串
     */
    fun smartTime(
        dateMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (dateMillis <= 0L) return ""

        val mailTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(dateMillis), zone)
        val nowTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val mailDate = mailTime.toLocalDate()
        val today = nowTime.toLocalDate()

        return when {
            // 同一天（含设备时钟轻微偏差导致的「未来几分钟」）
            mailDate == today -> mailTime.format(timeFormatter)

            isSameIsoWeek(mailDate, today) -> weekDayName(mailDate.dayOfWeek)

            mailDate.year == today.year -> mailDate.format(shortDateFormatter)

            else -> mailDate.format(fullDateFormatter)
        }
    }

    /** 完整时间（详情页使用）：`yyyy/MM/dd HH:mm` */
    fun fullTime(
        dateMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (dateMillis <= 0L) return ""
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(dateMillis), zone)
        return "${dateTime.toLocalDate().format(fullDateFormatter)} ${dateTime.format(timeFormatter)}"
    }

    /**
     * 相对时间（同步状态提示使用）：
     * `刚刚` / `N 分钟前` / `N 小时前` / `N 天前`。
     */
    fun relativeTime(
        dateMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        if (dateMillis <= 0L) return "从未"
        val diff = nowMillis - dateMillis
        if (diff < 0L) return "刚刚"

        val minutes = diff / 60_000L
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> "$minutes 分钟前"
            minutes < 60L * 24L -> "${minutes / 60L} 小时前"
            else -> "${minutes / (60L * 24L)} 天前"
        }
    }

    /** 中文星期名 */
    fun weekDayName(day: DayOfWeek): String = weekDayNames[day.value - 1]

    /**
     * 是否处于同一个 ISO 周。
     *
     * 使用「周编号 + 周年份」双字段比较，正确处理跨年周
     * （例如 12 月 31 日与次年 1 月 1 日可能属于同一周）。
     */
    fun isSameIsoWeek(a: LocalDate, b: LocalDate): Boolean {
        val fields = WeekFields.ISO
        return a.get(fields.weekOfWeekBasedYear()) == b.get(fields.weekOfWeekBasedYear()) &&
            a.get(fields.weekBasedYear()) == b.get(fields.weekBasedYear())
    }
}
