package com.haloged.watchmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.haloged.watchmail.data.local.entity.EmailEntity
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.CircularDisplayUtil
import com.haloged.watchmail.ui.util.LocalUiScale
import java.text.SimpleDateFormat
import java.util.*

/**
 * 圆形表盘列表项宽度适配修饰符
 *
 * 根据列表项在屏幕上的 Y 坐标动态计算可用弦长，避免内容被圆弧裁切。
 *
 * 流畅度要点：
 *  - **宽度按 12px 量化**后再写入 State：`onGloballyPositioned` 在布局阶段回调，
 *    写 State 会触发「重组 + 重布局」。量化到 12px 后，滚动一屏只重排几次而非每 4px 一次，
 *    是列表滚动帧率的关键。
 *  - 只在量化值变化时才写 State，相同值不触发重组。
 *  - 整体缩放（graphicsLayer）会让 positionInWindow() 返回缩放后坐标，
 *    先按 LocalUiScale 换算回未缩放坐标再求弦长。
 */
@Composable
fun Modifier.circularAdaptedWidth(): Modifier {
    val density = LocalDensity.current
    val uiScale = LocalUiScale.current
    // 量化后的宽度（px），默认取安全区直径
    var widthPx by remember {
        mutableFloatStateOf(CircularDisplayUtil.SAFE_RADIUS * 2f - CircularDisplayUtil.EDGE_BUFFER * 2f)
    }

    return this
        .onGloballyPositioned { coords ->
            val topY = coords.positionInWindow().y
            val centerYScaled = topY + coords.size.height / 2f
            // 还原到未缩放坐标：y = 圆心Y + (y' - 圆心Y) / scale
            val centerY = CircularDisplayUtil.SCREEN_CENTER_Y +
                    (centerYScaled - CircularDisplayUtil.SCREEN_CENTER_Y) / uiScale
            // 以中心 Y 求弦长（未缩放 px）
            val chord = CircularDisplayUtil.calculateChordWidth(centerY)
            // 12px 量化：降低重组频率，滚动更顺滑
            val quantized = (chord / 12f).toInt() * 12f
            if (quantized != widthPx) {
                widthPx = quantized
            }
        }
        .width(with(density) { widthPx.toDp() })
}

/**
 * 邮件列表项组件
 * 适配 466px 圆形表盘的邮件预览卡片
 *
 * 布局要点：
 *  - 宽度随 Y 坐标动态收窄（弦长），保证圆弧不裁切内容
 *  - 触控热区 ≥48dp
 *  - 字号 ≥14sp
 */
@Composable
fun EmailListItem(
    email: EmailEntity,
    accountColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 缓存时间格式化结果
    val formattedTime = remember(email.receivedAt) {
        formatEmailTime(email.receivedAt)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                // 关键：按所在 Y 坐标动态计算可用弦宽
                .circularAdaptedWidth()
                .heightIn(min = 38.dp) // 纵向收紧，一屏多显示几封
                .clip(RoundedCornerShape(11.dp))
                .background(WatchMailColors.Surface)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 账户颜色标识（来源账户）
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accountColor)
            )

            Spacer(modifier = Modifier.width(6.dp))

            // 未读标记（圆点）
            if (!email.isRead) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(WatchMailColors.UnreadDot)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            // 邮件内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 发件人（姓名或地址，单行截断）
                Text(
                    text = email.fromName?.takeIf { it.isNotBlank() } ?: email.fromAddress,
                    style = WatchMailTypography.EmailSender.copy(
                        // 未读加粗
                        fontWeight = if (email.isRead) FontWeight.Normal else FontWeight.Bold,
                        fontSize = WatchFontSizesLocal.SENDER
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 主题（单行截断）
                Text(
                    text = email.subject.ifBlank { "(无主题)" },
                    style = WatchMailTypography.EmailSubject.copy(
                        fontSize = WatchFontSizesLocal.SUBJECT
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 时间（智能格式）
            Text(
                text = formattedTime,
                style = WatchMailTypography.EmailTime.copy(
                    fontSize = WatchFontSizesLocal.TIME
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * 列表项内部字号常量（sp）
 * 全部 ≥14sp，符合圆形表盘最小字号规范
 */
private object WatchFontSizesLocal {
    val SENDER = 15.sp   // 发件人
    val SUBJECT = 14.sp  // 主题
    val TIME = 14.sp     // 时间
}

/**
 * 格式化邮件时间（智能格式）
 * 今天 → HH:mm；本周 → 周X；更早 → MM/DD
 */
private fun formatEmailTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val date = Calendar.getInstance().apply { timeInMillis = timestamp }

    return when {
        isSameDay(now, date) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        isSameWeek(now, date) -> {
            val dayNames = arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")
            dayNames[date.get(Calendar.DAY_OF_WEEK)]
        }
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isSameWeek(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.WEEK_OF_YEAR) == cal2.get(Calendar.WEEK_OF_YEAR)
}
