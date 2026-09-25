package com.haloged.watchmail.ui.util

import androidx.compose.ui.geometry.Rect
import kotlin.math.sqrt

/**
 * 圆形表盘UI适配工具类
 * 针对 466×466 px 正圆形表盘（圆心 (233,233)，半径 233px）
 *
 * 安全内容区域：以圆心为中心、半径 ≤ 210px 的圆形区域
 * 边缘 23px 作为缓冲区（半径 210 → 233）
 *
 * 所有几何计算以【像素】为单位，保证与需求规格一致。
 * Compose 层通过 Density 在 dp/px 之间换算（见 CircularListAdapter）。
 */
object CircularDisplayUtil {

    // 屏幕尺寸常量（像素）
    const val SCREEN_SIZE = 466
    const val SCREEN_CENTER_X = 233f
    const val SCREEN_CENTER_Y = 233f
    const val SCREEN_RADIUS = 233f

    // 安全区域常量：核心内容区半径 210px，边缘 23px 缓冲区
    const val SAFE_RADIUS = 210f
    const val EDGE_BUFFER = 23f

    // 状态栏和操作栏高度（像素）
    const val TOP_BAR_HEIGHT = 44f
    const val BOTTOM_BAR_HEIGHT = 52f

    // 最小触控热区（像素，≥48×48）
    const val MIN_TOUCH_TARGET = 48f

    /**
     * 计算指定Y坐标处的可用弦长（水平宽度）
     * 用于列表项动态宽度计算，避免内容被圆弧裁切
     *
     * 公式：半弦长 h = √(R² - dy²)，完整弦长 = 2h
     * 其中 dy = |y - 圆心Y|
     *
     * @param y 当前Y坐标（像素）
     * @param safeRadius 安全区域半径（像素）
     * @return 可用宽度（像素），已扣除左右缓冲
     */
    fun calculateChordWidth(y: Float, safeRadius: Float = SAFE_RADIUS): Float {
        val dy = Math.abs(y - SCREEN_CENTER_Y)

        // 超出安全区域半径则无可用宽度
        if (dy >= safeRadius) return 0f

        // 勾股定理求半弦长
        val halfChord = sqrt((safeRadius * safeRadius - dy * dy).toDouble()).toFloat()

        // 完整弦长减去左右缓冲区
        return (halfChord * 2 - EDGE_BUFFER * 2).coerceAtLeast(0f)
    }

    /**
     * 计算指定Y坐标处的可用X范围
     * @param y 当前Y坐标（像素）
     * @param safeRadius 安全区域半径（像素）
     * @return Pair<起始X, 结束X>（像素）
     */
    fun calculateXRange(y: Float, safeRadius: Float = SAFE_RADIUS): Pair<Float, Float> {
        val dy = Math.abs(y - SCREEN_CENTER_Y)

        if (dy >= safeRadius) return Pair(SCREEN_CENTER_X, SCREEN_CENTER_X)

        val halfChord = sqrt((safeRadius * safeRadius - dy * dy).toDouble()).toFloat()

        val startX = SCREEN_CENTER_X - halfChord + EDGE_BUFFER
        val endX = SCREEN_CENTER_X + halfChord - EDGE_BUFFER

        return Pair(startX, endX)
    }

    /**
     * 计算安全内容区域的边界矩形
     * @return 安全区域的Rect（像素）
     */
    fun getSafeContentRect(): Rect {
        return Rect(
            left = SCREEN_CENTER_X - SAFE_RADIUS + EDGE_BUFFER,
            top = SCREEN_CENTER_Y - SAFE_RADIUS + EDGE_BUFFER,
            right = SCREEN_CENTER_X + SAFE_RADIUS - EDGE_BUFFER,
            bottom = SCREEN_CENTER_Y + SAFE_RADIUS - EDGE_BUFFER
        )
    }

    /**
     * 计算可滚动列表区域
     * 排除顶部状态栏和底部操作栏
     * @return 列表区域的Rect（像素）
     */
    fun getScrollableListRect(): Rect {
        return Rect(
            left = SCREEN_CENTER_X - SAFE_RADIUS + EDGE_BUFFER,
            top = TOP_BAR_HEIGHT + 8f,
            right = SCREEN_CENTER_X + SAFE_RADIUS - EDGE_BUFFER,
            bottom = SCREEN_SIZE - BOTTOM_BAR_HEIGHT - 8f
        )
    }

    /**
     * 检查点是否在安全区域内
     */
    fun isPointInSafeArea(x: Float, y: Float, safeRadius: Float = SAFE_RADIUS): Boolean {
        val dx = x - SCREEN_CENTER_X
        val dy = y - SCREEN_CENTER_Y
        return (dx * dx + dy * dy) <= (safeRadius * safeRadius)
    }

    /**
     * 计算圆形裁剪区域
     * 用于创建圆形遮罩
     */
    fun getCircularClipRect(
        centerX: Float = SCREEN_CENTER_X,
        centerY: Float = SCREEN_CENTER_Y,
        radius: Float = SCREEN_RADIUS
    ): Rect {
        return Rect(
            left = centerX - radius,
            top = centerY - radius,
            right = centerX + radius,
            bottom = centerY + radius
        )
    }

    /**
     * 计算列表项的推荐宽度
     * 以列表项中心 Y 求弦长，再留出内边距，避免圆弧裁切
     *
     * @param y 列表项顶部Y坐标（像素）
     * @param maxHeight 列表项高度（像素）
     * @return 推荐宽度（像素）
     */
    fun getRecommendedItemWidth(y: Float, maxHeight: Float = 72f): Float {
        // 列表项中心点的Y坐标
        val centerY = y + maxHeight / 2f

        // 中心点处的弦长
        val centerWidth = calculateChordWidth(centerY)

        // 留出左右安全边距
        return (centerWidth - 16f).coerceAtLeast(0f)
    }

    /**
     * 计算渐进透明度（边缘渐隐）
     * 用于顶部/底部弧形区域内容的渐隐效果
     *
     * @param y 当前Y坐标（像素）
     * @return 透明度 (0.0 - 1.0)
     */
    fun getEdgeAlpha(y: Float): Float {
        val topEdge = TOP_BAR_HEIGHT + 24f
        val bottomEdge = SCREEN_SIZE - BOTTOM_BAR_HEIGHT - 24f

        return when {
            y < topEdge -> ((y - TOP_BAR_HEIGHT) / 24f).coerceIn(0f, 1f)
            y > bottomEdge -> ((SCREEN_SIZE - BOTTOM_BAR_HEIGHT - y) / 24f).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    /**
     * 计算表冠旋转对应的滚动增量
     * @param rotationDelta 旋转增量（像素）
     * @param sensitivity 灵敏度系数
     * @return 滚动像素值
     */
    fun calculateScrollFromCrown(rotationDelta: Float, sensitivity: Float = 50f): Float {
        return rotationDelta * sensitivity
    }

    /**
     * 获取最小触控热区尺寸（像素）
     */
    fun getMinTouchTargetSize(): Float = MIN_TOUCH_TARGET

    /**
     * 验证触控目标是否符合最小尺寸要求（≥48×48）
     */
    fun isValidTouchTarget(width: Float, height: Float): Boolean {
        return width >= MIN_TOUCH_TARGET && height >= MIN_TOUCH_TARGET
    }
}

/**
 * 圆形显示配置数据类
 * 用于存储和传递圆形显示参数（单位：像素）
 */
data class CircularDisplayConfig(
    val screenSize: Int = CircularDisplayUtil.SCREEN_SIZE,
    val centerX: Float = CircularDisplayUtil.SCREEN_CENTER_X,
    val centerY: Float = CircularDisplayUtil.SCREEN_CENTER_Y,
    val screenRadius: Float = CircularDisplayUtil.SCREEN_RADIUS,
    val safeRadius: Float = CircularDisplayUtil.SAFE_RADIUS,
    val edgeBuffer: Float = CircularDisplayUtil.EDGE_BUFFER,
    val topBarHeight: Float = CircularDisplayUtil.TOP_BAR_HEIGHT,
    val bottomBarHeight: Float = CircularDisplayUtil.BOTTOM_BAR_HEIGHT
)

/**
 * 字体大小常量（sp）
 * 遵循圆形表盘的字体规范：最小 14sp，正文 16-18sp，标题 20-22sp
 */
object WatchFontSizes {
    const val MINIMUM = 14f      // 最小字体（规范下限，不可突破）
    const val BODY = 16f         // 正文字体
    const val BODY_LARGE = 18f   // 大正文字体
    const val TITLE = 20f        // 标题字体
    const val TITLE_LARGE = 22f  // 大标题字体
}
