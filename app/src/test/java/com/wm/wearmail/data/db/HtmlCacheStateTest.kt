package com.wm.wearmail.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HtmlCacheState] 的单元测试（纯 JVM，无需设备）。
 *
 * 守护的核心契约（曾导致 P1 缺陷）：
 * `body_html` 的 SQL NULL 只表示「没问过」（[HtmlCacheState.UNKNOWN]），
 * 「问过但没有」必须写成空串（[HtmlCacheState.NONE]）；
 * 否则纯文本邮件会被反复判定为「缓存不完整」——既反复重下正文，又永远无法离线打开。
 */
class HtmlCacheStateTest {

    // ------------------------------------------------------------------
    // 列值 → 三态
    // ------------------------------------------------------------------

    @Test
    fun `SQL NULL 表示没问过`() {
        assertEquals(HtmlCacheState.UNKNOWN, HtmlCacheState.fromColumnValue(null))
        assertFalse(HtmlCacheState.fromColumnValue(null).resolved)
    }

    @Test
    fun `空串表示问过但没有`() {
        assertEquals(HtmlCacheState.NONE, HtmlCacheState.fromColumnValue(""))
        assertTrue(HtmlCacheState.fromColumnValue("").resolved)
    }

    @Test
    fun `非空文本表示已缓存 HTML`() {
        assertEquals(HtmlCacheState.AVAILABLE, HtmlCacheState.fromColumnValue("<p>hi</p>"))
        assertTrue(HtmlCacheState.fromColumnValue("<p>hi</p>").resolved)
    }

    // ------------------------------------------------------------------
    // 抓取结果 → 列值
    // ------------------------------------------------------------------

    @Test
    fun `抓到 HTML 时按原文写入，与是否显式请求无关`() {
        assertEquals("<p>hi</p>", HtmlCacheState.toColumnValue("<p>hi</p>", htmlRequested = true))
        assertEquals("<p>hi</p>", HtmlCacheState.toColumnValue("<p>hi</p>", htmlRequested = false))
    }

    @Test
    fun `请求了但没拿到写空串`() {
        assertEquals(HtmlCacheState.NONE_COLUMN, HtmlCacheState.toColumnValue(null, htmlRequested = true))
        // 空串入参等价于「没拿到」，与 null 走同一条规则
        assertEquals(HtmlCacheState.NONE_COLUMN, HtmlCacheState.toColumnValue("", htmlRequested = true))
    }

    @Test
    fun `没请求且没拿到保持 NULL`() {
        assertNull(HtmlCacheState.toColumnValue(null, htmlRequested = false))
        assertNull(HtmlCacheState.toColumnValue("", htmlRequested = false))
    }

    // ------------------------------------------------------------------
    // 往返一致性：写入列再读回，语义必须不变
    // ------------------------------------------------------------------

    @Test
    fun `三态往返后语义不变`() {
        assertEquals(
            HtmlCacheState.UNKNOWN,
            HtmlCacheState.fromColumnValue(HtmlCacheState.toColumnValue(null, htmlRequested = false)),
        )
        assertEquals(
            HtmlCacheState.NONE,
            HtmlCacheState.fromColumnValue(HtmlCacheState.toColumnValue(null, htmlRequested = true)),
        )
        assertEquals(
            HtmlCacheState.AVAILABLE,
            HtmlCacheState.fromColumnValue(HtmlCacheState.toColumnValue("<p>x</p>", htmlRequested = true)),
        )
    }

    // ------------------------------------------------------------------
    // 防回归：resolved 必须严格等于「不是 UNKNOWN」
    // ------------------------------------------------------------------

    @Test
    fun `只有 UNKNOWN 未决，其余均已有结论`() {
        val unresolved = HtmlCacheState.entries.filter { !it.resolved }
        assertEquals(listOf(HtmlCacheState.UNKNOWN), unresolved)
    }
}
