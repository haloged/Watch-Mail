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
    // 关键：把手势判定放在 onPostScroll（子节点先消费，剩下的才轮到我们），
    // 而不是 onPreScroll（父节点先抢）。早期实现在 onPreScroll 里无条件消费
    // `available.y > 0`，导致列表不在顶部时「向下拖回」的前 96dp 位移被吞掉 ——
    // 表现为列表滚不动，且松手就误触发一次全量同步。
    val nestedScrollConnection = remember(onPullToRefresh, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            /**
             * 列表消费完之后，把**它吃不下**的向下位移（`available.y > 0`）累计为下拉位移。
             * 列表还能往回滚时，这部分位移会被列表自己消费掉（`available.y == 0`），
             * 因此无需（也无法）在这里判断「是否已到顶」。
             */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (onPullToRefresh == null) return Offset.Zero
                // 只认手指拖动：惯性阶段的溢出位移不该把指示器顶出来
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                val room = (maxPullPx - pullOffset).coerceAtLeast(0f)
                val consumedByPull = available.y.coerceAtMost(room)
                if (consumedByPull <= 0f) return Offset.Zero
                pullOffset += consumedByPull
                return Offset(0f, consumedByPull)
            }

            /**
             * 手指反向拖动时先把已积累的下拉位移收回去（指示器跟着缩），
             * 收完之后列表才继续滚 —— 否则指示器会卡在屏幕上。
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (onPullToRefresh == null) return Offset.Zero
                if (available.y < 0f && pullOffset > 0f) {
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
 * 说明：LazyList 的 item offset 以「内容区」（顶部 contentPadding 内侧）为原点，
 * 本组件内部会先换算成屏幕坐标再套用圆形几何模型
 * （详见 [CircularMetrics.horizontalPaddingForItem] 的坐标系说明）。
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
            val layoutInfo = state.layoutInfo
            val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val paddingPx = if (info == null) {
                // 首帧尚未测量：按圆心处的最大可用宽度兜底，避免抖动
                CircularMetrics.horizontalPaddingFor(
                    top = CircularMetrics.CENTER_PX,
                    bottom = CircularMetrics.CENTER_PX,
                    radius = safeRadiusPx,
                    minWidthPx = minContentWidthPx,
                )
            } else {
                // 注意：info.offset 是「内容区坐标」（原点在顶部 contentPadding 内侧），
                // 必须通过 horizontalPaddingForItem 换算成屏幕坐标后再算弦长；
                // 直接使用 offset 会让最宽点整体下移一个顶部内边距（放大位置偏下）。
                CircularMetrics.horizontalPaddingForItem(
                    contentOffset = info.offset,
                    itemSize = info.size,
                    beforeContentPadding = layoutInfo.beforeContentPadding,
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
