package com.haloged.watchmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.util.HapticUtil

/**
 * 图标按钮组件（Wear OS适配）
 *
 * 尺寸 36dp（icon 20dp）：圆形表盘纵向空间有限，
 * 若用 48dp 会让固定栏必须抬高 30dp 才不被圆弧裁切，挤掉邮件列表空间。
 * 见 [com.haloged.watchmail.util.HapticUtil.ICON_BUTTON_SIZE_DP] 的推导。
 */
@Composable
fun WatchIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WatchMailColors.TextPrimary,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier,  // 仅用于外部定位（padding/offset 等）
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(HapticUtil.ICON_BUTTON_SIZE_DP.dp)
                .clip(CircleShape)
                .background(
                    if (isPressed) WatchMailColors.SurfaceVariant.copy(alpha = 0.8f)
                    else Color.Transparent
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,  // 禁用涟漪效果以提高性能
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.wear.compose.material.Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(HapticUtil.ICON_SIZE_DP.dp)
            )
        }
    }
}

/**
 * 文本按钮组件（Wear OS适配）
 */
@Composable
fun WatchTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isPressed) WatchMailColors.SurfaceVariant.copy(alpha = 0.8f)
                else WatchMailColors.SurfaceVariant
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.wear.compose.material.Text(
            text = text,
            style = WatchMailTypography.Button.copy(
                color = if (enabled) WatchMailColors.Primary else WatchMailColors.TextTertiary
            )
        )
    }
}