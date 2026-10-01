package com.wm.wearmail.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 圆形表盘几何计算的单元测试。
 *
 * 这些断言直接对应需求 1.2 的硬性约束，属于「设计约束的回归保护」：
 * 一旦有人改动安全半径或弦长算法导致内容溢出圆形，测试立即失败。
 */
class CircularMetricsTest {

    private val delta = 0.01f

    // ---------------- 基本常量 ----------------

    @Test
    fun `屏幕与安全区常量符合需求`() {
        assertEquals(466f, CircularMetrics.SCREEN_SIZE_PX, delta)
        assertEquals(233f, CircularMetrics.CENTER_PX, delta)
        assertEquals(210f, CircularMetrics.SAFE_RADIUS_PX, delta)
        // 边缘缓冲区 = 233 - 210 = 23px
        assertEquals(23f, CircularMetrics.EDGE_BUFFER_PX, delta)
        // 触控热区下限 48px
        assertEquals(48f, CircularMetrics.MIN_TOUCH_TARGET_PX, delta)
    }

    // ---------------- 弦长计算 ----------------

    @Test
    fun `圆心处弦长等于直径`() {
        // 在圆心高度，可用宽度即安全圆直径 2 * 210 = 420
        assertEquals(420f, CircularMetrics.chordWidthAt(CircularMetrics.CENTER_PX), delta)
        assertEquals(210f, CircularMetrics.halfChordAt(CircularMetrics.CENTER_PX), delta)
    }

    @Test
    fun `安全圆边界处弦长归零`() {
        // y = 圆心 - 半径 恰好落在圆上，水平可用宽度为 0
        val topEdge = CircularMetrics.CENTER_PX - CircularMetrics.SAFE_RADIUS_PX
        assertEquals(0f, CircularMetrics.halfChordAt(topEdge), delta)
        // 超出圆周则直接返回 0，避免 sqrt(负数)
        assertEquals(0f, CircularMetrics.halfChordAt(topEdge - 10f), delta)
        assertEquals(0f, CircularMetrics.halfChordAt(CircularMetrics.CENTER_PX + 500f), delta)
    }

    @Test
    fun `弦长关于圆心上下对称`() {
        val offset = 60f
        val above = CircularMetrics.chordWidthAt(CircularMetrics.CENTER_PX - offset)
        val below = CircularMetrics.chordWidthAt(CircularMetrics.CENTER_PX + offset)
        assertEquals(above, below, delta)
    }

    @Test
    fun `越远离圆心可用宽度单调递减`() {
        var previous = Float.MAX_VALUE
        // 从圆心向屏幕下沿逐步采样
        var y = CircularMetrics.CENTER_PX
        while (y <= CircularMetrics.CENTER_PX + CircularMetrics.SAFE_RADIUS_PX) {
            val width = CircularMetrics.chordWidthAt(y)
            assertTrue("y=$y 处宽度应递减：$width !> $previous", width <= previous + delta)
            previous = width
            y += 10f
        }
    }

    @Test
    fun `弦长满足勾股关系`() {
        // 取 y 距离圆心 120px，理论弦长 = 2 * sqrt(210^2 - 120^2)
        val dy = 120f
        val expected = 2f * kotlin.math.sqrt(210f * 210f - dy * dy)
        val actual = CircularMetrics.chordWidthAt(CircularMetrics.CENTER_PX + dy)
        assertEquals(expected, actual, delta)
    }

    // ---------------- 列表项宽度 ----------------

    @Test
    fun `列表项宽度取区间内离圆心最远的一端`() {
        // 项跨越圆心上方，最远端是 top
        val top = CircularMetrics.CENTER_PX - 200f
        val bottom = CircularMetrics.CENTER_PX - 100f
        val width = CircularMetrics.availableWidthFor(top, bottom)
        // 最远端 d=200，理论宽度 2*sqrt(210^2-200^2) ≈ 127.5
        val expected = 2f * kotlin.math.sqrt(210f * 210f - 200f * 200f)
        assertEquals(expected, width, 0.5f)
        // 必须小于圆心处宽度，说明确实做了收窄
        assertTrue(width < CircularMetrics.chordWidthAt(CircularMetrics.CENTER_PX))
    }

    @Test
    fun `列表项宽度不超过屏幕宽度`() {
        val width = CircularMetrics.availableWidthFor(top = 233f, bottom = 233f, radius = 400f)
        assertEquals(466f, width, delta)
    }

    @Test
    fun `完全位于安全圆外的列表项宽度为零`() {
        val width = CircularMetrics.availableWidthFor(top = 10f, bottom = 20f)
        assertEquals(0f, width, delta)
    }

    @Test
    fun `水平内边距左右对称且非负`() {
        val padding = CircularMetrics.horizontalPaddingFor(top = 60f, bottom = 120f)
        assertTrue(padding > 0f)
        // 内容宽度 + 左右内边距 = 屏幕宽度
        val width = CircularMetrics.availableWidthFor(60f, 120f)
        assertEquals(466f, width + padding * 2f, 0.5f)
    }

    @Test
    fun `最小内容宽度兜底可避免极端收窄`() {
        // 位于极边缘时弦长趋近 0，但兜底宽度保证内容仍可操作
        val padding = CircularMetrics.horizontalPaddingFor(
            top = CircularMetrics.CENTER_PX - 209f,
            bottom = CircularMetrics.CENTER_PX - 208f,
            minWidthPx = 260f,
        )
        // 260 兜底 => 内边距 = (466-260)/2 = 103
        assertEquals(103f, padding, 0.5f)
    }

    // ---------------- 命中与触控 ----------------

    @Test
    fun `安全圆内外判定正确`() {
        assertTrue(CircularMetrics.isInsideSafeCircle(233f, 233f))
        assertTrue(CircularMetrics.isInsideSafeCircle(233f + 209f, 233f))
        assertFalse(CircularMetrics.isInsideSafeCircle(233f + 211f, 233f))
        // 屏幕四角(0,0)必然在圆外
        assertFalse(CircularMetrics.isInsideSafeCircle(0f, 0f))
    }

    @Test
    fun `触控热区下限校验`() {
        assertTrue(CircularMetrics.meetsTouchTarget(48f, 48f))
        assertTrue(CircularMetrics.meetsTouchTarget(60f, 50f))
        assertFalse(CircularMetrics.meetsTouchTarget(47.9f, 48f))
        assertFalse(CircularMetrics.meetsTouchTarget(48f, 40f))
    }

    @Test
    fun `顶部状态栏宽度小于圆心处宽度`() {
        val statusWidth = CircularMetrics.topStatusBarWidth()
        assertTrue(statusWidth > 0f)
        assertTrue(statusWidth < CircularMetrics.chordWidthAt(CircularMetrics.CENTER_PX))
        // 半个状态栏宽度即为可用半弦长，不应超过安全半径
        assertTrue(abs(statusWidth / 2f) <= CircularMetrics.SAFE_RADIUS_PX)
    }
}
