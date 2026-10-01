package com.wm.wearmail.ui.kit

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 圆形表盘几何计算（纯函数，无 Android 依赖，可单元测试）。
 *
 * 坐标系：以屏幕左上角为原点，x 向右、y 向下；屏幕 466x466，
 * 圆形表盘圆心为 (233, 233)，有效显示区域为内切圆（半径 233）。
 *
 * 安全内容区：半径 210px（边缘留 23px 缓冲），所有核心内容都应落在该圆内。
 *
 * 核心问题是「弦长」：在高度 y 处，圆内可用水平宽度为
 *     2 * sqrt(r² - (y - cy)²)
 * 越靠近表盘上下边缘，可用宽度越小；列表项必须按此动态收窄，
 * 否则会被圆弧裁切（这是圆形表盘列表最常见的错位问题）。
 */
object CircularMetrics {

    /** 屏幕边长（px） */
    const val SCREEN_SIZE_PX: Float = 466f

    /** 圆心坐标（px） */
    const val CENTER_PX: Float = SCREEN_SIZE_PX / 2f

    /** 安全内容区半径（px）：需求规定 ≤ 210px */
    const val SAFE_RADIUS_PX: Float = 210f

    /** 边缘缓冲区宽度（px）：233 - 210 = 23 */
    const val EDGE_BUFFER_PX: Float = CENTER_PX - SAFE_RADIUS_PX

    /** 触控热区最小边长（px）：需求规定不小于 48x48 */
    const val MIN_TOUCH_TARGET_PX: Float = 48f

    /** 最小正文字号（sp 对应的 px 基准）：需求规定不低于 14px */
    const val MIN_FONT_SIZE_SP: Float = 14f

    /**
     * 计算 y 高度处的半弦长（圆心到圆内水平边界的距离）。
     *
     * @return 半弦长；当 y 落在安全圆之外时返回 0（无可用宽度）
     */
    fun halfChordAt(y: Float, radius: Float = SAFE_RADIUS_PX): Float {
        val dy = abs(y - CENTER_PX)
        if (dy >= radius) return 0f
        return sqrt(radius * radius - dy * dy)
    }

    /** 计算 y 高度处的可用弦长（即该行的最大可用宽度） */
    fun chordWidthAt(y: Float, radius: Float = SAFE_RADIUS_PX): Float =
        2f * halfChordAt(y, radius)

    /**
     * 计算列表项 [top, bottom] 纵向区间内的可用宽度。
     *
     * 取区间内「离圆心最远」的那一端计算弦长，从而保证整行都不被裁切
     * （宁可略窄，也不出现圆角处文字被切掉）。
     */
    fun availableWidthFor(
        top: Float,
        bottom: Float,
        radius: Float = SAFE_RADIUS_PX,
    ): Float {
        // 归一化：确保 top <= bottom
        val t = minOf(top, bottom)
        val b = maxOf(top, bottom)
        val dyTop = abs(t - CENTER_PX)
        val dyBottom = abs(b - CENTER_PX)
        val dyMax = maxOf(dyTop, dyBottom)
        if (dyMax >= radius) return 0f
        val width = 2f * sqrt(radius * radius - dyMax * dyMax)
        // 宽度不可能超过屏幕本身
        return width.coerceAtMost(SCREEN_SIZE_PX)
    }

    /**
     * 列表项为避免被圆弧裁切所需的水平内边距。
     *
     * @param minWidthPx 兜底最小内容宽度（例如长按菜单需要的最小可点区域），
     *        避免位于极边缘时宽度被压缩到无法使用
     */
    fun horizontalPaddingFor(
        top: Float,
        bottom: Float,
        radius: Float = SAFE_RADIUS_PX,
        minWidthPx: Float = 0f,
    ): Float {
        val width = availableWidthFor(top, bottom, radius).coerceAtLeast(minWidthPx)
        return ((SCREEN_SIZE_PX - width) / 2f).coerceAtLeast(0f)
    }

    /** 判断某点是否落在安全内容圆内 */
    fun isInsideSafeCircle(
        x: Float,
        y: Float,
        radius: Float = SAFE_RADIUS_PX,
    ): Boolean {
        val dx = x - CENTER_PX
        val dy = y - CENTER_PX
        return dx * dx + dy * dy <= radius * radius
    }

    /** 触控热区是否满足 48x48px 的下限 */
    fun meetsTouchTarget(widthPx: Float, heightPx: Float): Boolean =
        widthPx >= MIN_TOUCH_TARGET_PX && heightPx >= MIN_TOUCH_TARGET_PX

    /**
     * 顶部弧形区域（表盘上沿）的可用宽度下限常量：
     * 用于让状态栏/时间文字自动收窄，避免与圆弧相交。
     */
    fun topStatusBarWidth(radius: Float = SAFE_RADIUS_PX): Float =
        chordWidthAt(CENTER_PX - radius * 0.86f, radius)
}
