package com.haloged.watchmail.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 震动/触觉反馈工具
 *
 * ⚠ Wear OS 各厂商触觉实现差异很大（OPPO/ColorOS Watch 尤其明显）：
 *  - `VibrationEffect.createOneShot(10ms)` 太短，很多机型直接丢弃 → 表现为"没震动"
 *  - 厂商系统触觉引擎（`View.performHapticFeedback`）与 `Vibrator` 是两条独立链路，
 *    有些机型只打通其中一条
 *  - 因此本类对每种反馈都采用「双链路 + 多级降级」，保证至少一条生效：
 *      ① 系统触觉引擎（CLOCK_TICK / VIRTUAL_KEY / CONFIRM / REJECT）
 *      ② Vibrator 预定义效果（EFFECT_TICK / EFFECT_CLICK）
 *      ③ Vibrator 波形（时长 + 显式振幅，避开 DEFAULT_AMPLITUDE 的厂商兼容坑）
 */
object HapticUtil {

    /** 表冠刻度步进（像素）：滚过这么多像素给一次刻度震动 */
    const val CROWN_NOTCH_STEP_PX = 28f

    /**
     * 图标按钮视觉/触控盒尺寸（dp）
     *
     * ⚠ 从 48dp 收到 36dp：圆形表盘纵向空间极其有限 ——
     * 48dp 按钮要求固定栏抬高 30dp 才能避开圆弧，三栏共占 408px，邮件列表只剩 58px。
     * 36dp 按钮只需抬高 18dp，chrome 降到 268px，列表恢复到 198px（约 2.5 条）。
     */
    const val ICON_BUTTON_SIZE_DP = 36
    const val ICON_SIZE_DP = 20

    // ==================== 基础设施 ====================

    /**
     * 获取 Vibrator（兼容 API 31+ 的 VibratorManager）
     */
    private fun getVibrator(context: Context): Vibrator? {
        return try {
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
    }

    /**
     * 执行系统触觉引擎反馈
     * @return 是否确实触发（false 表示该链路不可用，需降级）
     */
    private fun systemHaptic(view: View?, constant: Int): Boolean {
        if (view == null) return false
        return try {
            // FLAG_IGNORE_GLOBAL_SETTING：即使用户关了系统触觉也要给反馈
            view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Vibrator 刻度震动（预定义 TICK 优先，再降级）
     */
    private fun vibrateTick(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (!vibrator.hasVibrator()) return

            when {
                // ① 预定义 TICK —— 表冠/滚轮刻度的标准触感，OPPO Wear 支持最好
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
                // ② 显式振幅的 oneShot。25ms 保证被硬件识别（10ms 会被丢弃）
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, 180))
                }
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25)
                }
            }
        } catch (t: Throwable) {
            // ③ 最后降级：波形
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, 200))
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Vibrator 长震动（确认/警告用）
     */
    private fun vibratePulse(context: Context, durationMs: Long, amplitude: Int = 200) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (t: Throwable) {
        }
    }

    // ==================== 表冠 ====================

    /**
     * 表冠刻度震动（旋转时每滚过一格调用一次）
     *
     * 双链路同时触发：两者都是 ~20ms 短震，硬件上会叠加成一次「哒」的刻度感；
     * 若某条链路被厂商禁用，另一条仍能生效 —— 这是 OPPO 表能震动的关键。
     */
    fun performCrownTick(context: Context, view: View? = null) {
        systemHaptic(view, HapticFeedbackConstants.CLOCK_TICK)
        vibrateTick(context)
    }

    /**
     * 表冠滚动反馈（旧入口，等价于 performCrownTick）
     */
    fun performCrownScrollFeedback(context: Context) {
        performCrownTick(context, null)
    }

    /**
     * 表冠按下（表冠键按下时）
     */
    fun performCrownPress(context: Context, view: View? = null) {
        systemHaptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
        vibratePulse(context, 30, 220)
    }

    // ==================== 通用交互 ====================

    /**
     * 轻触反馈（点击按钮等）
     */
    fun performLightFeedback(view: View, context: Context? = null) {
        val ok = systemHaptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
        if (!ok && context != null) vibrateTick(context)
    }

    /**
     * 确认反馈（操作成功等）
     */
    fun performConfirmFeedback(view: View, context: Context? = null) {
        val ok = systemHaptic(view, HapticFeedbackConstants.CONFIRM)
        if (ok) return
        if (context != null) {
            vibratePulse(context, 40, 220)
        } else {
            // 无 context 时退回最短可用时长
            systemHaptic(view, HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * 拒绝反馈（操作失败等）
     */
    fun performRejectFeedback(view: View, context: Context? = null) {
        val ok = systemHaptic(view, HapticFeedbackConstants.REJECT)
        if (ok) return
        if (context != null) {
            // 双击波形，模拟"拒绝"节奏
            try {
                val v = getVibrator(context)
                if (v != null && v.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 35, 60, 35),
                            intArrayOf(0, 200, 0, 200),
                            -1
                        )
                    )
                }
            } catch (t: Throwable) {
            }
        }
    }

    /**
     * 长按反馈
     */
    fun performLongPressFeedback(view: View, context: Context? = null) {
        val ok = systemHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        if (!ok && context != null) vibratePulse(context, 35, 200)
    }

    // ==================== 通用震动 ====================

    /**
     * 直接震动（保留旧 API）
     */
    fun vibrate(context: Context, durationMs: Long = 50) {
        vibratePulse(context, durationMs, 200)
    }
}
