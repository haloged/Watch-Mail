package com.haloged.watchmail.ui.util

import android.util.Log
import android.view.View
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.haloged.watchmail.util.HapticUtil
import kotlin.math.abs

/**
 * 表冠（旋转表圈/数码表冠）滚动适配
 *
 * ⚠ 为什么要同时处理两条事件链路：
 *  Wear OS 上表冠的事件形态由厂商决定，用 `dumpsys input` 查设备分类即可判定：
 *
 *  ┌──────────────────────┬───────────────────────────┬──────────────────────────────┐
 *  │ dumpsys input 显示    │ 框架归类                   │ 应该监听什么                  │
 *  ├──────────────────────┼───────────────────────────┼──────────────────────────────┤
 *  │ Sources 0x00004000   │ ROTARY_ENCODER            │ onRotaryScrollEvent          │
 *  │ Sources 0x00002002   │ SOURCE_MOUSE（鼠标滚轮）   │ PointerEvent.scrollDelta     │
 *  └──────────────────────┴───────────────────────────┴──────────────────────────────┘
 *
 *  实测 OPPO Watch X2（OWW251）：`oplus_crown`
 *     Classes: 0x00000008 (INPUT_DEVICE_CLASS_CURSOR)
 *     Sources: 0x00002002 (SOURCE_MOUSE)
 *     input props: <none>  （缺 INPUT_PROP_ROTARY_ENCODER）
 *  → 系统把表冠当**鼠标滚轮**派发（MotionEvent.ACTION_SCROLL / AXIS_VSCROLL），
 *    `onRotaryScrollEvent` **完全不会触发**。这就是"表冠震动没适配"的根因。
 *
 *  ⚠ 事件落点问题：
 *    滚轮事件的坐标可能是 (0,0)（表冠没有触点位置），而圆形裁剪外没有子节点，
 *    事件会直接落到根布局。因此除了在滚动容器上监听，还必须在根布局做兜底转发。
 *
 *  最终结构：
 *    [滚动容器] crownScroll(state)   → 注册到 Registry + 处理真旋转编码器 + 处理滚轮
 *    [根布局]   crownWheelRouter()   → 没被消费的滚轮事件转发给当前活跃滚动容器
 */

private const val TAG = "CrownScroll"

/** 鼠标滚轮每"格"对应的滚动像素 */
private const val WHEEL_PIXELS_PER_NOTCH = 48f

/**
 * 当前活跃的滚动目标登记表
 * 导航栈上同一时刻只有一个页面可滚动，取栈顶即可
 */
object CrownScrollRegistry {
    private val targets = ArrayDeque<ScrollableState>()

    fun register(state: ScrollableState) {
        if (!targets.contains(state)) targets.addLast(state)
    }

    fun unregister(state: ScrollableState) {
        targets.remove(state)
    }

    fun active(): ScrollableState? = targets.lastOrNull()
}

/** 滚动累加器：滚过一个刻度给一次震动（用于旋转编码器的连续像素值） */
private class CrownAccumulator {
    var value: Float = 0f
}

/**
 * 把一次"格"数转换成滚动 + 刻度震动
 * @return 是否处理了
 */
private fun handleWheelNotches(
    state: ScrollableState?,
    notches: Float,
    context: android.content.Context,
    view: View
): Boolean {
    if (state == null || notches == 0f || !notches.isFinite()) return false
    state.dispatchRawDelta(-notches * WHEEL_PIXELS_PER_NOTCH)
    // 每格一次刻度震动 —— 这正是表冠的"哒"手感
    HapticUtil.performCrownTick(context, view)
    return true
}

/**
 * 为任意 ScrollableState（LazyListState / ScrollState）接入表冠滚动 + 刻度震动
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.crownScroll(state: ScrollableState): Modifier {
    val context = LocalContext.current
    val view: View = LocalView.current
    val focusRequester = remember { FocusRequester() }
    val accumulator = remember { CrownAccumulator() }

    // 登记为当前可滚动目标，供根布局兜底转发
    DisposableEffect(state) {
        CrownScrollRegistry.register(state)
        onDispose { CrownScrollRegistry.unregister(state) }
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (t: Throwable) {
            // 某些机型在窗口未就绪时会抛 IllegalStateException，忽略即可
        }
    }

    return this
        .focusRequester(focusRequester)
        .focusable()

        // ======== 链路 A：真旋转编码器（emulator / 标准 Wear） ========
        .onRotaryScrollEvent { event ->
            val delta = event.verticalScrollPixels
            if (delta != 0f && delta.isFinite()) {
                state.dispatchRawDelta(delta)

                accumulator.value += delta
                if (abs(accumulator.value) >= HapticUtil.CROWN_NOTCH_STEP_PX) {
                    accumulator.value = 0f
                    HapticUtil.performCrownTick(context, view)
                }
                Log.d(TAG, "rotary delta=$delta")
            }
            true // 消费事件，保证后续滚动继续派发过来
        }

        // ======== 链路 B：鼠标滚轮型表冠（OPPO oplus_crown） ========
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    // 滚轮/表冠事件的 scrollDelta 才非零；普通触屏拖动恒为 Offset.Zero
                    val deltaY = change.scrollDelta.y
                    if (deltaY != 0f && !change.isConsumed) {
                        change.consume()
                        if (handleWheelNotches(state, deltaY, context, view)) {
                            Log.d(TAG, "wheel(local) notches=$deltaY")
                        }
                    }
                }
            }
        }
}

/**
 * 根布局兜底：转发没被滚动容器消费的滚轮事件
 *
 * 表冠事件的坐标常为 (0,0)（无触点位置），圆形表盘上 (0,0) 落在裁剪外，
 * 子节点收不到，必须由根布局接收后转发给当前活跃的滚动容器。
 */
@Composable
fun Modifier.crownWheelRouter(): Modifier {
    val context = LocalContext.current
    val view: View = LocalView.current

    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val deltaY = change.scrollDelta.y
                if (deltaY != 0f && !change.isConsumed) {
                    val target = CrownScrollRegistry.active()
                    if (target != null) {
                        change.consume()
                        if (handleWheelNotches(target, deltaY, context, view)) {
                            Log.d(TAG, "wheel(routed) notches=$deltaY -> ${target.javaClass.simpleName}")
                        }
                    } else {
                        Log.d(TAG, "wheel(no target) notches=$deltaY")
                    }
                }
            }
        }
    }
}
