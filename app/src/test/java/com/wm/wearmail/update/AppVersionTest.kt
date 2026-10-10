package com.wm.wearmail.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号解析与比较的单元测试。
 *
 * 重点守护两个方向的错误：
 * 1. **「有新版本却不提示」**：按字符串直接比较会把 `1.10.0` 判小于 `1.9.0`；
 * 2. **「误报更新」**：解析失败时绝不能返回"有新版本"（宁可不提醒也不误报）。
 */
class AppVersionTest {

    @Test
    fun `解析常见格式`() {
        assertEquals(listOf(1, 2, 0), AppVersion.parse("1.2.0"))
        assertEquals(listOf(1, 2, 0), AppVersion.parse("v1.2.0"))
        assertEquals(listOf(1, 2, 0), AppVersion.parse("V1.2.0"))
        assertEquals(listOf(1, 2), AppVersion.parse("1.2"))
        assertEquals(listOf(2), AppVersion.parse("v2"))
        assertEquals(listOf(1, 2, 0, 4), AppVersion.parse("1.2.0.4"))
    }

    @Test
    fun `忽略预发布与构建元数据后缀`() {
        assertEquals(listOf(1, 2, 0), AppVersion.parse("1.2.0-beta.1"))
        assertEquals(listOf(1, 2, 0), AppVersion.parse("v1.2.0-rc2"))
        assertEquals(listOf(1, 2, 0), AppVersion.parse("1.2.0+build.7"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("abc"))
        assertNull(AppVersion.parse("1.a.0"))
        assertNull(AppVersion.parse("1..2"))
    }

    @Test
    fun `高版本判定`() {
        assertTrue(AppVersion.isNewer("1.2.0", "1.0.0"))
        assertTrue(AppVersion.isNewer("1.2.1", "1.2.0"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.9.9"))
        // 字符串直接比较会在这两例上出错：1.10.0 < 1.9.0、v1.2.0 ≠ 1.2.0
        assertTrue(AppVersion.isNewer("1.10.0", "1.9.0"))
        assertTrue(AppVersion.isNewer("v1.2.0", "1.1.0"))
        assertTrue(AppVersion.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `低版本与相等判定`() {
        assertFalse(AppVersion.isNewer("1.0.0", "1.2.0"))
        assertFalse(AppVersion.isNewer("1.2.0", "1.2.0"))
        assertFalse(AppVersion.isNewer("1.9.0", "1.10.0"))
        // 缺位按 0 补齐：1.0 与 1.0.0 视为相同版本
        assertFalse(AppVersion.isNewer("1.0.0", "1.0"))
        assertFalse(AppVersion.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `解析失败时恒返回 false`() {
        assertFalse(AppVersion.isNewer("abc", "1.0.0"))
        assertFalse(AppVersion.isNewer("2.0.0", "abc"))
        assertFalse(AppVersion.isNewer("", ""))
    }
}
