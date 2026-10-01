package com.wm.wearmail.ui.kit

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.wm.wearmail.core.RotaryBus
import kotlinx.coroutines.flow.collectLatest

/**
 * 圆形表盘安全滚动列表。
 *
 * 解决的问题：矩形 [LazyColumn] 直接铺在圆形表盘上时，
 * 靠近上下边缘的列表项会被圆弧裁切。
 *
 * 方案：把列表铺满整屏（视口即屏幕），每个列表项通过 [CircularItem]
 * 根据自身在屏幕中的实际 Y 区间动态计算可用弦长，并施加对应的水平内边距，
 * 从而使内容始终落在半径 210px 的安全圆内。
 *
 * 附带能力：
 * - [rotaryBus]：把表冠旋转事件路由到本列表（仅在 [active] 为 true 时生效，
 *   避免横向分页时多个列表同时响应）；
 * - [onPullToRefresh]：基于 NestedScroll 的下拉刷新（列表已在顶部时才触发）。
 */
@Composable
fun CircularSafeLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    safeRadiusPx: Float = CircularMetrics.SAFE_RADIUS_PX,
    /**
     * 列表内边距。
     *
     * 默认值说明（density≈2.0 时 1dp = 2px）：
     * - 顶部 52dp ≈ 104px：避开顶部弧形的 [androidx.wear.compose.material3.TimeText] 时间文字；
     * - 底部 56dp ≈ 112px：避开底部弧形区域的「新建邮件」主按钮。
     */
    contentPadding: PaddingValues = PaddingValues(top = 52.dp, bottom = 56.dp),
    /** 当前页面是否处于活动状态（决定是否响应该页面的表冠事件） */
    active: Boolean = true,
    rotaryBus: RotaryBus? = null,
    /** 非空时启用下拉刷新 */
    onPullToRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { PullThreshold.toPx() }
    val maxPullPx = with(density) { MaxPull.toPx() }

    // 下拉位移（px）。用普通 state 即可，位移变化频率低且只影响顶部指示器。
    var pullOffset by remember { mutableFloatStateOf(0f) }

    // 刷新结束后收起指示器
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullOffset = 0f
    }

    // ---------------- 表冠滚动 ----------------
    if (rotaryBus != null && active) {
        LaunchedEffect(rotaryBus, state) {
            rotaryBus.events.collectLatest { delta ->
                val step = delta.coerceIn(-RotaryBus.MAX_STEP_PX, RotaryBus.MAX_STEP_PX)
                // scrollBy 为 ScrollableState 的挂起扩展，内部已处理边界与惯性
                state.scrollBy(step)
            }
        }
    }

    // ---------------- 下拉刷新 ----------------
    val nestedScrollConnection = remember(onPullToRefresh, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            /**
             * 在列表消费滚动之前介入：
             * - 手指向下拖（available.y > 0）且列表已到顶时，把位移转成「下拉位移」并消费掉；
             * - 手指向上拖时优先把已积累的下拉位移收回，避免指示器卡住。
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (onPullToRefresh == null) return Offset.Zero
                if (available.y > 0f) {
                    val room = (maxPullPx - pullOffset).coerceAtLeast(0f)
                    val consumed = available.y.coerceAtMost(room)
                    if (consumed > 0f) {
                        pullOffset += consumed
                        return Offset(0f, consumed)
                    }
                } else if (available.y < 0f && pullOffset > 0f) {
                    val consumed = (-available.y).coerceAtMost(pullOffset)
                    pullOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            /** 手势结束时判定是否达到刷新阈值 */
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (onPullToRefresh != null && pullOffset >= thresholdPx) {
                    onPullToRefresh.invoke()
                }
                pullOffset = 0f
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )

        if (onPullToRefresh != null && (pullOffset > 0f || isRefreshing)) {
            PullRefreshIndicator(
                pullOffset = pullOffset,
                thresholdPx = thresholdPx,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * 列表项容器：按其在屏幕上的实际 Y 区间动态收窄宽度。
 *
 * 用法（[index] 必须是该项在列表中的稳定索引）：
 * ```
 * CircularSafeLazyColumn(state = listState) {
 *     itemsIndexed(mails, key = { _, m -> m.id }) { index, mail ->
 *         CircularItem(index = index, state = listState) { rowModifier ->
 *             MailRow(mail, rowModifier)
 *         }
 *     }
 * }
 * ```
 *
 * @param minContentWidthPx 兜底内容宽度（px）。位于表盘上下极边缘时，
 *        弦长会趋近 0，若不设下限会导致内容被压成一条线而无法操作。
 */
@Composable
fun CircularItem(
    index: Int,
    state: LazyListState,
    safeRadiusPx: Float = CircularMetrics.SAFE_RADIUS_PX,
    minContentWidthPx: Float = 260f,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val horizontalPadding: Dp by remember(index, safeRadiusPx, minContentWidthPx, density) {
        derivedStateOf {
            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val paddingPx = if (info == null) {
                // 首帧尚未测量：按圆心处的最大可用宽度兜底，避免抖动
                CircularMetrics.horizontalPaddingFor(
                    top = CircularMetrics.CENTER_PX,
                    bottom = CircularMetrics.CENTER_PX,
                    radius = safeRadiusPx,
                    minWidthPx = minContentWidthPx,
                )
            } else {
                CircularMetrics.horizontalPaddingFor(
                    top = info.offset.toFloat(),
                    bottom = (info.offset + info.size).toFloat(),
                    radius = safeRadiusPx,
                    minWidthPx = minContentWidthPx,
                )
            }
            with(density) { paddingPx.toDp() }
        }
    }

    content(modifier.padding(horizontal = horizontalPadding))
}

/**
 * 下拉刷新指示器：贴在表盘上沿的弧形区域内。
 */
@Composable
private fun PullRefreshIndicator(
    pullOffset: Float,
    thresholdPx: Float,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 指示器高度随下拉位移增长，最多两个阈值高度
    val height: Dp = with(density) { pullOffset.coerceIn(0f, thresholdPx * 2f).toDp() }

    Box(
        modifier = modifier.height(height.coerceAtLeast(if (isRefreshing) 24.dp else 0.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (isRefreshing) {
            LoadingIndicator(size = 20.dp)
        } else if (pullOffset > 0f) {
            Text(
                text = if (pullOffset >= thresholdPx) "松开刷新" else "下拉刷新",
                style = androidx.wear.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 下拉刷新触发阈值 */
private val PullThreshold: Dp = 48.dp

/** 下拉位移上限 */
private val MaxPull: Dp = 96.dp
