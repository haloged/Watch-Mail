package com.wm.wearmail.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * 账户标识色卡。
 *
 * 8 种色相均匀分布的亮色，保证在纯黑表盘上对比度充足（WCAG 对比度 > 4.5:1），
 * 且相邻色在色觉障碍下仍可区分（蓝/绿/橙/红/紫/青/黄/粉）。
 */
val AccountColors: List<Color> = listOf(
    Color(0xFF5AA9FF), // 蓝
    Color(0xFF41D17A), // 绿
    Color(0xFFFFA733), // 橙
    Color(0xFFFF5C7A), // 红
    Color(0xFFC77DFF), // 紫
    Color(0xFF2FD4C6), // 青
    Color(0xFFFFD84D), // 黄
    Color(0xFFFF8FC7), // 粉
)

/** 未读高亮色 */
val UnreadAccent: Color = Color(0xFF8FC7FF)

/** 发送成功色 */
val SuccessColor: Color = Color(0xFF4CD964)

/** 取账户标识色（自动取模，任何索引都安全） */
fun accountColor(index: Int): Color {
    val size = AccountColors.size
    return AccountColors[((index % size) + size) % size]
}

/**
 * 深色表盘配色方案。
 *
 * 设计要点：
 * - 背景纯黑：OLED 手表显示黑像素不耗电，且圆形遮罩外区域视觉上完全消失；
 * - 主色偏浅蓝：在深色背景上足够亮，抬腕扫一眼即可辨识；
 * - 错误色偏亮红：删除/失败状态需要立即被注意到。
 */
private val WearMailDarkScheme: ColorScheme = ColorScheme(
    primary = Color(0xFF9FC9FF),
    primaryDim = Color(0xFF6FA8E8),
    primaryContainer = Color(0xFF1B3B5F),
    onPrimary = Color(0xFF00325B),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF9AD0C6),
    secondaryDim = Color(0xFF6FA9A0),
    secondaryContainer = Color(0xFF123B36),
    onSecondary = Color(0xFF00382F),
    onSecondaryContainer = Color(0xFFCDEFE8),
    tertiary = Color(0xFFFFD8A8),
    tertiaryDim = Color(0xFFE0B47C),
    tertiaryContainer = Color(0xFF4A3200),
    onTertiary = Color(0xFF3F2A00),
    onTertiaryContainer = Color(0xFFFFEBC9),
    surfaceContainerLow = Color(0xFF0E1216),
    surfaceContainer = Color(0xFF161B20),
    surfaceContainerHigh = Color(0xFF20262C),
    onSurface = Color(0xFFE4E7EA),
    onSurfaceVariant = Color(0xFFA9B1B9),
    outline = Color(0xFF6E767E),
    outlineVariant = Color(0xFF394147),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE4E7EA),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFD98C85),
    errorContainer = Color(0xFF5C1A16),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 应用主题。
 *
 * 排版（Typography）沿用 Wear Material3 默认值：其字号阶梯本身即为圆形表盘
 * 设计（bodyMedium 14sp 为最小正文字号，满足「字体最小不低于 14px」的要求），
 * 需要更大字号时在组件上显式覆盖 fontSize 即可。
 */
@Composable
fun WearMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WearMailDarkScheme, content = content)
}
