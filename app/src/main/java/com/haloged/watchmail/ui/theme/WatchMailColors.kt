package com.haloged.watchmail.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WatchMail颜色主题
 * 专为圆形表盘设计的配色方案
 */
object WatchMailColors {
    // 主色调
    val Primary = Color(0xFF4A90E2)        // 蓝色主色
    val PrimaryDark = Color(0xFF2E6BB0)    // 深蓝
    val PrimaryLight = Color(0xFF7AB3EF)   // 浅蓝
    
    // 背景色
    val Background = Color(0xFF000000)     // 纯黑背景（省电）
    val Surface = Color(0xFF1A1A1A)        // 深灰表面
    val SurfaceVariant = Color(0xFF2D2D2D) // 灰色表面变体
    
    // 文本颜色
    val TextPrimary = Color(0xFFFFFFFF)    // 白色主文本
    val TextSecondary = Color(0xFFB0B0B0)  // 灰色次文本
    val TextTertiary = Color(0xFF808080)   // 浅灰第三文本
    
    // 状态颜色
    val UnreadDot = Color(0xFF4A90E2)      // 未读标记（蓝色）
    val Success = Color(0xFF4CAF50)        // 成功（绿色）
    val Error = Color(0xFFE53935)          // 错误（红色）
    val Warning = Color(0xFFFFA726)        // 警告（橙色）
    
    // 账户标识颜色（用于区分不同账户）
    val AccountColors = listOf(
        Color(0xFF4A90E2),  // 蓝色
        Color(0xFFE53935),  // 红色
        Color(0xFF4CAF50),  // 绿色
        Color(0xFFFFA726),  // 橙色
        Color(0xFF9C27B0),  // 紫色
        Color(0xFF00BCD4),  // 青色
        Color(0xFFFF5722),  // 深橙
        Color(0xFF795548),  // 棕色
    )
    
    /**
     * 根据账户索引获取标识颜色
     */
    fun getAccountColor(index: Int): Color {
        return AccountColors[index % AccountColors.size]
    }
    
    /**
     * 根据颜色值获取Color对象
     */
    fun getColorFromLong(colorLong: Long): Color {
        return Color(colorLong.toULong())
    }
    
    /**
     * 将Color转换为Long值（用于存储）
     */
    fun colorToLong(color: Color): Long {
        return color.value.toLong()
    }
}
