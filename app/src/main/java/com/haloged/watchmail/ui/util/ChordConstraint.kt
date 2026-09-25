package com.haloged.watchmail.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 圆弧宽度约束修饰符
 *
 * 圆形表盘上，越靠近上下边缘可用弦宽越窄。固定栏（顶栏/底栏/筛选栏）若用 `fillMaxWidth()`
 * 把按钮排到画布左右两端，必然被圆弧裁切 —— 这才是"按钮显示不全"的根因，
 * 单纯整体缩放解决不了（画布缩小后按钮仍贴在画布边缘）。
 *
 * 本修饰符测量自身 Y 范围，取**最窄处**的弦长，把内容宽度限制在可见弧内并**水平居中**，
 * 保证栏内所有按钮完整落在圆内。
 *
 * ⚠ 不能用 `Modifier.width()` 实现：外层的 `fillMaxWidth()` 会把约束强制成父宽，
 *   里面的 `width()` 被 coerce 掉、完全不生效。必须用自定义 Layout 自己定宽并居中。
 */
@Composable
fun Modifier.chordConstrained(marginPx: Float = 14f): Modifier {
    val uiScale = LocalUiScale.current
    var widthPx by remember { mutableFloatStateOf(-1f) }

    val positioned = this.onGloballyPositioned { coords ->
        if (coords.size.height <= 0) return@onGloballyPositioned

        val topWindow = coords.positionInWindow().y
        val bottomWindow = topWindow + coords.size.height

        // 整体缩放（graphicsLayer）后 positionInWindow 返回的是缩放后坐标，
        // 先换算回未缩放坐标再求弦长
        val c = CircularDisplayUtil.SCREEN_CENTER_Y
        val top = c + (topWindow - c) / uiScale
        val bottom = c + (bottomWindow - c) / uiScale

        // 取整栏最窄处的原始弦长（不做缓冲扣减，只留少量余量）
        val chord = minOf(rawChordWidth(top), rawChordWidth(bottom)) - marginPx
        val quantized = (chord / 4f).toInt() * 4f
        if (quantized > 0 && quantized != widthPx) {
            widthPx = quantized
        }
    }

    return positioned.layout { measurable, constraints ->
        // 目标宽度：已算出弦宽则用之，否则退回父宽
        val target = if (widthPx > 0) widthPx.toInt() else constraints.maxWidth
        val placeable = measurable.measure(
            constraints.copy(minWidth = 0, maxWidth = target.coerceAtMost(constraints.maxWidth))
        )
        // 撑满父宽以便居中，内容自身收窄到弦宽
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative((constraints.maxWidth - placeable.width) / 2, 0)
        }
    }
}

/**
 * 指定 Y 处的原始弦长（px），不扣缓冲
 */
private fun rawChordWidth(y: Float): Float {
    val dy = abs(y - CircularDisplayUtil.SCREEN_CENTER_Y)
    val r = CircularDisplayUtil.SCREEN_RADIUS
    if (dy >= r) return 0f
    return 2f * sqrt((r * r - dy * dy).toDouble()).toFloat()
}

/**
 * 给固定栏用的「上/下留白」常量（dp）
 *
 * 抬高栏内容，使其落在弦宽足够的位置。值由按钮尺寸反推：
 *  - 底栏：36dp 按钮完整落在圆内，内容底部 Y 处弦宽需 ≥ 3×72=216px → 下留白 18dp
 *  - 顶栏：同理，内容顶部 Y 处弦宽需 ≥ 2×72+标题 → 上留白 18dp
 * 见 README「圆形适配」推导表。
 */
object ChordInsets {
    const val TOP_BAR_TOP_PADDING_DP = 18
    const val BOTTOM_BAR_BOTTOM_PADDING_DP = 18
}
