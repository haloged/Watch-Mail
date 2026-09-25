package com.haloged.watchmail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.haloged.watchmail.ui.screen.account.AccountScreen
import com.haloged.watchmail.ui.screen.account.AddAccountScreen
import com.haloged.watchmail.ui.screen.account.EditAccountScreen
import com.haloged.watchmail.ui.screen.account.PairScreen
import com.haloged.watchmail.ui.screen.compose.ComposeScreen
import com.haloged.watchmail.ui.screen.detail.EmailDetailScreen
import com.haloged.watchmail.ui.screen.drafts.DraftsScreen
import com.haloged.watchmail.ui.screen.inbox.InboxScreen
import com.haloged.watchmail.ui.screen.settings.SettingsScreen
import com.haloged.watchmail.ui.screen.splash.SplashScreen
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.util.LocalUiScale
import com.haloged.watchmail.ui.util.crownWheelRouter
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil

/**
 * 主Activity
 * Wear OS应用的入口点
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点击跳转邮件详情的 Intent extra 键 */
        const val EXTRA_OPEN_EMAIL_ID = "email_id"
    }

    /** 通知运行时权限请求（Android 13+ POST_NOTIFICATIONS） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 授权与否都继续运行，仅影响能否弹出通知
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 申请通知权限（Android 13+ 必需，否则通知被系统静默拦截）
        requestNotificationPermission()

        // 处理通知点击带来的邮件详情跳转
        val openEmailId = intent?.getLongExtra(EXTRA_OPEN_EMAIL_ID, -1L) ?: -1L

        setContent {
            WearApp(openEmailId = openEmailId.takeIf { it > 0 })
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // singleTask 模式下，后续通知点击走 onNewIntent
        // 由于 Compose 导航状态在 remember 中，这里由系统重建 Activity 兜底
    }

    /**
     * 申请通知权限
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * Wear OS应用根组件
 * @param openEmailId 通知点击时需要直接打开的邮件ID（可空）
 */
@Composable
fun WearApp(openEmailId: Long? = null) {
    val navController = rememberSwipeDismissableNavController()
    val viewModel: MainViewModel = viewModel()
    val context = LocalContext.current
    val view = LocalView.current

    // 整体界面缩放（默认 100% = 1.0x）：graphicsLayer 等比缩放整个画面，四周露出黑边
    val settings by viewModel.settings.collectAsState()
    val uiScale = settings.uiScale

    // 通知点击 → 直接跳转到对应邮件详情
    LaunchedEffect(openEmailId) {
        if (openEmailId != null && openEmailId > 0) {
            HapticUtil.performConfirmFeedback(view, context)
            navController.navigate("email_detail/$openEmailId") {
                // 通知进入时清空返回栈，避免回退到开屏/空白页
                popUpTo("inbox")
            }
        }
    }

    CompositionLocalProvider(LocalUiScale provides uiScale) {
        // 外层黑色画布：缩放后露出的就是这块黑底
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 整体等比缩放（含文字、图标、间距），中心对齐 → 缩小后四周留黑边
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = uiScale
                        scaleY = uiScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
            ) {
            MaterialTheme(
                colors = wearColorPalette()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WatchMailColors.Background)
                        // 表冠兜底路由：OPPO 等把表冠当鼠标滚轮上报，且事件坐标常为 (0,0)，
                        // 落在圆形裁剪外导致子节点收不到，必须由根布局转发
                        .crownWheelRouter()
                        // 无滚动容器页面时的表冠刻度震动
                        .onRotaryScrollEvent { event ->
                            if (event.verticalScrollPixels != 0f) {
                                HapticUtil.performCrownTick(context, view)
                            }
                            false // 不消费，交给子组件
                        }
                ) {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "splash"
            ) {
                // 开屏动画（logo 缩放淡入 + 进度点，约 1.6s，可点击跳过）
                composable("splash") {
                    SplashScreen(
                        onFinished = {
                            navController.navigate("inbox") {
                                // 开屏页不进返回栈
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    )
                }

                // 收件箱页面（主页面）
                composable("inbox") {
                    InboxScreen(
                        viewModel = viewModel,
                        onEmailClick = { emailId ->
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("email_detail/$emailId")
                        },
                        onComposeClick = {
                            HapticUtil.performLightFeedback(view)
                            viewModel.clearComposePrefill()
                            navController.navigate("compose")
                        },
                        onSettingsClick = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("settings")
                        },
                        onAccountsClick = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("accounts")
                        }
                    )
                }

                // 邮件详情页面
                composable("email_detail/{emailId}") { backStackEntry ->
                    val emailId = backStackEntry.arguments?.getString("emailId")?.toLongOrNull()
                    if (emailId != null) {
                        EmailDetailScreen(
                            emailId = emailId,
                            viewModel = viewModel,
                            onBack = {
                                HapticUtil.performLightFeedback(view)
                                viewModel.clearSelectedEmail()
                                navController.popBackStack()
                            },
                            onReply = {
                                HapticUtil.performLightFeedback(view)
                                // 回复：由详情页把收件人/主题/引用正文写入 composePrefill 后跳转
                                navController.navigate("compose")
                            }
                        )
                    }
                }

                // 撰写邮件页面
                composable("compose") {
                    ComposeScreen(
                        viewModel = viewModel,
                        onBack = {
                            HapticUtil.performLightFeedback(view)
                            navController.popBackStack()
                        },
                        onSendSuccess = {
                            HapticUtil.performConfirmFeedback(view)
                            navController.popBackStack()
                        }
                    )
                }

                // 草稿箱页面
                composable("drafts") {
                    DraftsScreen(
                        viewModel = viewModel,
                        onBack = {
                            HapticUtil.performLightFeedback(view)
                            navController.popBackStack()
                        },
                        onDraftClick = { draft ->
                            HapticUtil.performLightFeedback(view)
                            // 草稿带入撰写页继续编辑
                            viewModel.setComposePrefill(
                                com.haloged.watchmail.ui.viewmodel.ComposePrefill(
                                    toAddress = draft.toAddress,
                                    subject = draft.subject,
                                    body = draft.body,
                                    accountId = draft.accountId
                                )
                            )
                            viewModel.deleteDraft(draft.id)
                            navController.navigate("compose")
                        }
                    )
                }

                // 账户管理页面
                composable("accounts") {
                    AccountScreen(
                        viewModel = viewModel,
                        onBack = {
                            HapticUtil.performLightFeedback(view)
                            navController.popBackStack()
                        },
                        onAddAccount = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("add_account")
                        },
                        onEditAccount = { accountId ->
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("edit_account/$accountId")
                        },
                        onPairQr = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("pair_qr")
                        }
                    )
                }

                // 扫码配对添加账户页面（手表起本地 Web 服务 + 二维码）
                composable("pair_qr") {
                    PairScreen(
                        viewModel = viewModel,
                        onBack = {
                            HapticUtil.performLightFeedback(view)
                            navController.popBackStack()
                        },
                        onPairSuccess = {
                            HapticUtil.performConfirmFeedback(view)
                            // 配对成功后直接回收到账户列表
                            navController.popBackStack()
                        }
                    )
                }

                // 添加账户页面
                composable("add_account") {
                    AddAccountScreen(
                        viewModel = viewModel,
                        onBack = {
                            HapticUtil.performLightFeedback(view)
                            navController.popBackStack()
                        },
                        onSuccess = {
                            HapticUtil.performConfirmFeedback(view)
                            navController.popBackStack()
                        },
                        onPairQr = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("pair_qr")
                        }
                    )
                }

                // 编辑账户页面
                composable("edit_account/{accountId}") { backStackEntry ->
                    val accountId = backStackEntry.arguments?.getString("accountId")?.toLongOrNull()
                    if (accountId != null) {
                        EditAccountScreen(
                            accountId = accountId,
                            viewModel = viewModel,
                            onBack = {
                                HapticUtil.performLightFeedback(view)
                                navController.popBackStack()
                            },
                            onSuccess = {
                                HapticUtil.performConfirmFeedback(view)
                                navController.popBackStack()
                            }
                        )
                    }
                }

                // 设置页面
                composable("settings") {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            HapticUtil.performLightFeedback(view)
                            navController.popBackStack()
                        },
                        onAccountClick = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("accounts")
                        },
                        onDraftsClick = {
                            HapticUtil.performLightFeedback(view)
                            navController.navigate("drafts")
                        }
                    )
                }
            }
            }
        }
        }
    }
}
}

/**
 * Wear OS Material主题颜色配置
 */
@Composable
private fun wearColorPalette(): Colors {
    return Colors(
        primary = WatchMailColors.Primary,
        primaryVariant = WatchMailColors.PrimaryDark,
        secondary = WatchMailColors.PrimaryLight,
        secondaryVariant = WatchMailColors.PrimaryLight,
        background = WatchMailColors.Background,
        surface = WatchMailColors.Surface,
        error = WatchMailColors.Error,
        onPrimary = WatchMailColors.TextPrimary,
        onSecondary = WatchMailColors.TextPrimary,
        onBackground = WatchMailColors.TextPrimary,
        onSurface = WatchMailColors.TextPrimary,
        onError = WatchMailColors.TextPrimary
    )
}
