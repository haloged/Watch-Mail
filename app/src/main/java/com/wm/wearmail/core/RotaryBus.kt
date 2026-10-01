package com.wm.wearmail.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 表冠（旋转输入）事件总线。
 *
 * 为什么不用 Compose 的 rotary 修饰符：
 * - 本应用的可滚动控件是自实现的 [com.wm.wearmail.ui.kit.CircularSafeLazyColumn]，
 *   需要把旋转事件精确路由到「当前可见页面」的列表；
 * - 由 Activity 统一接收 `MotionEvent`（AXIS_SCROLL + SOURCE_ROTARY_ENCODER）
 *   再广播给 UI，可避免横向分页与纵向滚动争夺同一个手势通道。
 *
 * 事件单位为「滚动像素」（正数向下、负数向上），
 * 订阅方按 0.6 的系数平滑到列表滚动，避免高分辨率表冠滚动过快。
 */
class RotaryBus {

    private val _events = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 旋转事件流（单位：像素） */
    val events: SharedFlow<Float> = _events.asSharedFlow()

    /** 由 Activity 在收到表冠事件时调用 */
    fun onRotate(deltaPx: Float) {
        if (deltaPx == 0f) return
        _events.tryEmit(deltaPx * SENSITIVITY)
    }

    companion object {
        /** 表冠灵敏度：1 单位旋转量折算的滚动像素比例 */
        const val SENSITIVITY: Float = 0.6f

        /** 单次事件最大滚动像素，防止异常设备产生巨大跳变 */
        const val MAX_STEP_PX: Float = 120f
    }
}
