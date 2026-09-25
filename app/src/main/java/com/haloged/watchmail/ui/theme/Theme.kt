package com.haloged.watchmail.ui.theme

import androidx.compose.runtime.Composable

/**
 * WatchMail主题
 * 使用Wear OS Compose的MaterialTheme，不需要material3
 */
@Composable
fun WatchMailTheme(
    content: @Composable () -> Unit
) {
    // 使用Wear OS Compose的MaterialTheme
    // 在MainActivity中已配置
    content()
}
