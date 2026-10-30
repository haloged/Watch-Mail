package com.wm.wearmail.core

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志文本格式化的单元测试（纯函数）。
 *
 * 守护点：
 * - 一行一日志（换行必须转义，否则上传后的日志结构会被打乱）；
 * - 文件名按本地时区生成 `wearmail-yyyy-MM-dd.log`；
 * - 超长消息截断（避免一条日志占满整个文件）。
 */
class LogTextTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `生成带时间级别与标签的单行日志`() {
        // 2026-10-30 11:54:23.123 UTC
        val now = 1_793_361_263_123L
        val line = LogText.oneLine("I", "SyncEngine", "同步完成：3 封新邮件", now = now, timeZone = utc)
        assertTrue("行首应为时间：$line", line.startsWith("11:54:23.123 [I] SyncEngine: "))
        assertTrue(line.endsWith("同步完成：3 封新邮件"))
        assertTrue("必须是单行", !line.contains('\n'))
    }

    @Test
    fun `换行与制表符被压平`() {
        val sanitized = LogText.sanitize("第一行\n第二行\r\n第三行\tTab")
        assertEquals("第一行⏎第二行⏎第三行 Tab", sanitized)
        assertTrue(!sanitized.contains('\n'))
    }

    @Test
    fun `超长消息截断并追加省略标记`() {
        val long = "x".repeat(1_500)
        val sanitized = LogText.sanitize(long, maxChars = 1_000)
        assertEquals(1_001, sanitized.length) // 1000 + 省略号
        assertTrue(sanitized.endsWith("…"))
    }

    @Test
    fun `文件名按本地时区取当天`() {
        val name = LogText.fileNameFor(1_793_361_263_123L, utc)
        assertEquals("wearmail-2026-10-30.log", name)
    }
}
