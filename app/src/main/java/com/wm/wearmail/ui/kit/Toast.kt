package com.wm.wearmail.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

/** 提示停留时长（毫秒）：够读完一行中文，又不至于碍事 */
private const val TOAST_DURATION_MS = 2_200L

/**
 * 轻量提示浮层（Toast 样式）：深色胶囊、屏幕居中、自动消失。
 *
 * 为什么需要它（来自真机反馈的两个具体问题）：
 * - 添加账户页的「用手机扫码配置」入口在列表**顶部**，而提示文本原本渲染在
 *   列表**底部** —— 无 Wi-Fi 时用户点击后"什么都没发生"；
 * - 设置页的「检查更新」按钮在列表**底部**，提示却在**顶部** —— 同样看不到。
 *
 * 共同解法：把交互反馈放进**浮层**，无论用户正看着列表的哪一段都能看到；
 * 2.2 秒后自动消失，不打断操作（不拦截触摸：纯绘制节点，指针事件穿透到下层）。
 *
 * 用法：
 * ```kotlin
 * var toast by remember { mutableStateOf<String?>(null) }
 * // 触发：toast = "请先连接 Wi-Fi"
 * // 在 CircularScreen 内容末尾：
 * ToastHost(message = toast, onDismiss = { toast = null })
 * ```
 *
 * @param message 非空时显示；到时后调用 [onDismiss]（由调用方把状态置回 null）。
 *        显示期间重复赋**相同**文案不会重新计时；置回 null 后再触发可重弹。
 */
@Composable
fun ToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 保留最后一条非空文案：退场动画期间 message 已为 null，
    // 若直接读 message 文字会先消失再淡出（闪烁）
    var lastMessage by remember { mutableStateOf("") }

    LaunchedEffect(message) {
        if (message != null) {
            lastMessage = message
            delay(TOAST_DURATION_MS)
            onDismiss()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.9f, animationSpec = tween(120)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.9f, animationSpec = tween(160)),
        ) {
            Text(
                text = lastMessage,
                modifier = Modifier
                    .background(
                        // 注意：Wear M3 没有 surfaceContainerHighest（那是手机版 M3 的颜色），
                        // 用 surfaceContainerHigh（与 SecondaryActionButton 同款底色）
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
