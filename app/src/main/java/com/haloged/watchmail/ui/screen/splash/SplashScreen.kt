package com.haloged.watchmail.ui.screen.splash

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏动画页
 *
 * 动画编排（总时长约 1.6s）：
 *  1. logo 从 0.55 倍放大到 1.0，同时淡入（500ms，回弹缓动）
 *  2. 副标题淡入（延迟 300ms）
 *  3. 底部进度点循环闪动，暗示正在加载本地邮件
 *  4. 结束后回调 onFinished 进入收件箱
 *
 * 期间任意点击可跳过 —— 不阻塞用户。
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit
) {
    val context = LocalContext.current

    // 解码透明 logo（drawable-nodpi 下的高分辨率 PNG）
    val logoBitmap = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, com.haloged.watchmail.R.drawable.ic_splash_logo)
                ?.asImageBitmap()
        }.getOrNull()
    }

    // logo 缩放 + 透明度
    val scale = remember { Animatable(0.55f) }
    val alpha = remember { Animatable(0f) }
    // 副标题
    val subAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 520, easing = FastOutSlowIn)
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 380, easing = LinearEasing)
            )
        }
        launch {
            delay(300)
            subAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 360, easing = LinearEasing)
            )
        }
        // 总时长结束后进入主界面
        delay(1600)
        onFinished()
    }

    // 底部进度点的循环动画
    val pulse = rememberInfiniteTransition(label = "splashPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splashPulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
            // 点击任意处跳过开屏
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFinished
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // logo
            if (logoBitmap != null) {
                Image(
                    bitmap = logoBitmap,
                    contentDescription = "WatchMail",
                    modifier = Modifier
                        .size(168.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            this.alpha = alpha.value
                        }
                )
            } else {
                // 资源缺失时的兜底：文字 logo
                Text(
                    text = "W",
                    style = WatchMailTypography.TitleLarge.copy(
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = WatchMailColors.Primary
                    ),
                    modifier = Modifier.alpha(alpha.value)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 应用名
            Text(
                text = "WatchMail",
                style = WatchMailTypography.Title.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = WatchMailColors.TextPrimary
                ),
                modifier = Modifier.alpha(subAlpha.value)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "手表上的邮箱",
                style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextSecondary),
                modifier = Modifier.alpha(subAlpha.value)
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 进度点
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(pulseAlpha)
                    .background(WatchMailColors.Primary, CircleShapeLocal)
            )
        }
    }
}

/** 圆形形状 */
private val CircleShapeLocal = androidx.compose.foundation.shape.CircleShape

/** 快出慢入缓动 */
private val FastOutSlowIn = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
