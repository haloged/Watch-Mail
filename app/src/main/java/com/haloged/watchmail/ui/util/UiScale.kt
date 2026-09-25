package com.haloged.watchmail.ui.util

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 整体界面缩放系数（1.0 = 原始大小）
 *
 * 由 MainActivity 在最外层通过 graphicsLayer 整体缩放整个应用画面，
 * 缩小后四周露出黑色画布 —— 这就是"缩小到有黑边"的效果。
 *
 * 圆形几何计算（circularAdaptedWidth）需要这个系数来换算回未缩放坐标，
 * 否则 positionInWindow() 拿到的是缩放后的坐标，弦长算出来会失真。
 */
val LocalUiScale = staticCompositionLocalOf { 1f }
