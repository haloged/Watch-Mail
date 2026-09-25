package com.haloged.watchmail.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * WatchMail字体样式
 * 遵循圆形表盘的字体规范
 */
object WatchMailTypography {
    
    // 大标题 - 用于页面标题
    val TitleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = WatchMailColors.TextPrimary
    )
    
    // 标题 - 用于列表项标题
    val Title = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        color = WatchMailColors.TextPrimary
    )
    
    // 正文大字 - 用于重要正文
    val BodyLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextPrimary
    )
    
    // 正文 - 用于普通正文
    val Body = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextPrimary
    )
    
    // 正文小字 - 用于次要信息
    val BodySmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextSecondary
    )
    
    // 说明文字 - 用于辅助信息（规范下限 14sp，不可更小）
    val Caption = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextTertiary
    )
    
    // 按钮文字
    val Button = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = WatchMailColors.TextPrimary
    )
    
    // 列表项标题
    val ListItemTitle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = WatchMailColors.TextPrimary
    )
    
    // 列表项副标题
    val ListItemSubtitle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextSecondary
    )
    
    // 邮件发件人
    val EmailSender = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = WatchMailColors.TextPrimary
    )
    
    // 邮件主题
    val EmailSubject = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextSecondary
    )
    
    // 邮件时间
    val EmailTime = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = WatchMailColors.TextTertiary
    )
}
