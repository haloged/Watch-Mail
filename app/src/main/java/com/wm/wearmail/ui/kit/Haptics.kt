package com.wm.wearmail.ui.kit

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 触觉反馈封装。
 *
 * 需求要求「所有操作需提供触觉反馈」，手表端触觉是主要的确认手段
 * （屏幕小、看不清状态变化）。
 *
 * 不同语义使用不同振动模式，让用户「盲操作」也能区分结果：
 * - [tick]：轻点（滚动到边界、切换筛选项）
 * - [longPress]：长按（弹出操作菜单）
 * - [success]：短-短两下（发送成功、同步完成）
 * - [error]：长-更长两下（发送失败、认证失败）
 *
 * 实现同时兼容 API 30（[Vibrator]）与 API 31+（[VibratorManager]），
 * 且所有调用都对异常静默兜底：设备无振动马达时不应崩溃。
 */
class WearHaptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (t: Throwable) {
        null
    }

    /** 轻点反馈 */
    fun tick() = oneShot(durationMillis = 18)

    /** 长按反馈 */
    fun longPress() = oneShot(durationMillis = 40)

    /** 成功反馈：两下短振 */
    fun success() = waveform(longArrayOf(0L, 28L, 70L, 28L))

    /** 失败反馈：长 + 更长，不易与成功混淆 */
    fun error() = waveform(longArrayOf(0L, 60L, 90L, 140L))

    private fun oneShot(durationMillis: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(
                VibrationEffect.createOneShot(
                    durationMillis,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        }
    }

    private fun waveform(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            // repeat = -1 表示不重复
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}

/** 组合项内获取（记忆化的）触觉反馈工具 */
@Composable
fun rememberHaptics(): WearHaptics {
    val context = LocalContext.current
    return remember(context) { WearHaptics(context) }
}
