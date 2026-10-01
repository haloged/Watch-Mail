package com.wm.wearmail.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.RotaryBus
import com.wm.wearmail.ui.screen.accounts.AccountListScreen
import com.wm.wearmail.ui.screen.accounts.AddAccountScreen
import com.wm.wearmail.ui.screen.compose.ComposeMailScreen
import com.wm.wearmail.ui.screen.detail.EmailDetailScreen
import com.wm.wearmail.ui.screen.inbox.InboxScreen
import com.wm.wearmail.ui.screen.settings.SettingsScreen
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.launch

/**
 * 顶层页面。
 *
 * 顺序即横向分页顺序，决定了滑动方向与页面的对应关系：
 * 手指向左滑（内容左移）→ 进入下一页 → 账户管理；
 * 手指向右滑 → 回到上一页 → 设置。
 * 这正好对应需求「左滑进入账户管理，右滑进入设置」，且默认落在中间的统一收件箱。
 */
enum class TopLevelPage(val title: String) {
    SETTINGS("设置"),
    INBOX("收件箱"),
    ACCOUNTS("账户"),
}

/**
 * 覆盖层页面（全屏盖在分页之上，由返回键或页面内返回按钮关闭）。
 */
sealed interface OverlayRoute {
    /** 邮件详情 */
    data class Detail(val emailId: Long) : OverlayRoute

    /** 撰写邮件（可携带回复预填或草稿 id） */
    data class Compose(
        val prefillTo: String? = null,
        val prefillSubject: String? = null,
        val draftId: Long? = null,
    ) : OverlayRoute

    /** 添加账户 */
    data object AddAccount : OverlayRoute
}

/**
 * 应用根组合项。
 *
 * 导航结构说明：
 * - 一级导航用横向分页（[HorizontalPager]）实现「左滑账户 / 右滑设置」，
 *   比引入 Navigation 组件更轻量，也天然支持手势预览下一页；
 * - 二级页面（详情、撰写、添加账户）用覆盖层实现，配 [BackHandler] 支持返回；
 * - 系统级「右滑退出应用」已在 themes.xml 中关闭，避免与右滑翻页冲突。
 *
 * 关于表冠：仅当前可见且无覆盖层时，才把旋转事件交给该页列表，
 * 防止三个页面同时滚动。
 */
@Composable
fun WearMailApp(
    container: AppContainer,
    rotaryBus: RotaryBus,
    initialEmailId: Long? = null,
    onInitialEmailConsumed: () -> Unit = {},
) {
    val pages = TopLevelPage.entries
    val pagerState = rememberPagerState(
        initialPage = TopLevelPage.INBOX.ordinal,
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()

    // 覆盖层路由（null 表示停留在分页页面）
    var overlay by remember { mutableStateOf<OverlayRoute?>(null) }

    // 通知点击进入详情：由 Activity 通过 Intent extra 传入
    LaunchedEffect(initialEmailId) {
        if (initialEmailId != null) {
            overlay = OverlayRoute.Detail(initialEmailId)
            onInitialEmailConsumed()
        }
    }

    // 返回键：优先关闭覆盖层
    BackHandler(enabled = overlay != null) {
        overlay = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val page = pages[pageIndex]
            // 只有「当前页 + 无覆盖层」时该页才是活动的
            val isActive = pagerState.currentPage == pageIndex && overlay == null

            when (page) {
                TopLevelPage.INBOX -> InboxScreen(
                    container = container,
                    active = isActive,
                    rotaryBus = rotaryBus,
                    onOpenEmail = { emailId -> overlay = OverlayRoute.Detail(emailId) },
                    onCompose = { overlay = OverlayRoute.Compose() },
                    onOpenAccounts = {
                        scope.launch { pagerState.animateScrollToPage(TopLevelPage.ACCOUNTS.ordinal) }
                    },
                    onOpenSettings = {
                        scope.launch { pagerState.animateScrollToPage(TopLevelPage.SETTINGS.ordinal) }
                    },
                )

                TopLevelPage.ACCOUNTS -> AccountListScreen(
                    container = container,
                    active = isActive,
                    rotaryBus = rotaryBus,
                    onAddAccount = { overlay = OverlayRoute.AddAccount },
                    onBack = {
                        scope.launch { pagerState.animateScrollToPage(TopLevelPage.INBOX.ordinal) }
                    },
                )

                TopLevelPage.SETTINGS -> SettingsScreen(
                    container = container,
                    active = isActive,
                    rotaryBus = rotaryBus,
                )
            }
        }

        // ---------------- 覆盖层 ----------------
        // 遮罩层（在内容层之下、分页之上）：不透明背景 + 吞掉所有指针事件。
        //
        // 两个作用：
        // 1) 视觉：覆盖层与分页是同一个 Box 的兄弟节点，若只画内容不画背景，
        //    空白处会直接透出后面的分页（「添加账户页能看到上一页」的根因）；
        // 2) 交互：空白处的点击/滑动否则会穿透到分页，造成误切换页面或误开邮件。
        if (overlay != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { change -> change.consume() }
                            }
                        }
                    },
            )
        }

        when (val route = overlay) {
            null -> Unit

            is OverlayRoute.Detail -> EmailDetailScreen(
                container = container,
                emailId = route.emailId,
                onBack = { overlay = null },
                onReply = { to, subject ->
                    overlay = OverlayRoute.Compose(prefillTo = to, prefillSubject = subject)
                },
                rotaryBus = rotaryBus,
            )

            is OverlayRoute.Compose -> ComposeMailScreen(
                container = container,
                prefillTo = route.prefillTo,
                prefillSubject = route.prefillSubject,
                draftId = route.draftId,
                onBack = { overlay = null },
                onSent = { overlay = null },
            )

            OverlayRoute.AddAccount -> AddAccountScreen(
                container = container,
                onBack = { overlay = null },
                onDone = { overlay = null },
            )
        }
    }
}
