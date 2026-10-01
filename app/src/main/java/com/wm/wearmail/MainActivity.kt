package com.wm.wearmail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.core.RotaryBus
import com.wm.wearmail.ui.nav.WearMailApp
import com.wm.wearmail.ui.theme.WearMailTheme

/**
 * 唯一 Activity（单 Activity + Compose 架构）。
 *
 * 职责：
 * 1. 承载 Compose 内容树；
 * 2. 前台生命周期内启动/停止定时同步；
 * 3. 接收表冠（旋转编码器）事件并广播给 UI；
 * 4. 处理通知点击带来的 Intent（携带邮件 id，直接进入详情）。
 *
 * 冷启动路径上不访问数据库、不发起网络请求，只做容器构造（惰性）与
 * 通知渠道检查，以满足 < 2 秒的启动要求。
 */
class MainActivity : ComponentActivity() {

    private val rotaryBus = RotaryBus()

    private lateinit var container: AppContainer

    /** 待打开的邮件 id（来自通知点击），由 Compose 消费后清空 */
    private var pendingEmailId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = AppContainer.from(this)

        // Android 13+ 需要运行时申请通知权限，否则新邮件通知会被静默丢弃
        requestNotificationPermissionIfNeeded()

        // 处理启动 Intent 中可能携带的邮件 id
        pendingEmailId = extractEmailId(intent)

        setContent {
            WearMailTheme {
                WearMailApp(
                    container = container,
                    rotaryBus = rotaryBus,
                    initialEmailId = pendingEmailId,
                    onInitialEmailConsumed = { pendingEmailId = null },
                )
            }
        }

        Logs.i(TAG, "MainActivity 创建完成")
    }

    override fun onStart() {
        super.onStart()
        // 前台定时同步（默认 5 分钟），随 Activity 可见性启停，避免后台耗电
        runCatching { container.sync.startForegroundLoop(lifecycleScope) }
            .onFailure { Logs.e(TAG, "启动前台同步循环失败", it) }
    }

    override fun onStop() {
        runCatching { container.sync.stopForegroundLoop() }
            .onFailure { Logs.e(TAG, "停止前台同步循环失败", it) }
        // 离开界面时释放配对服务端口
        runCatching { container.pairing.stop() }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Activity 为 singleTop，重复点击通知复用本实例
        extractEmailId(intent)?.let { pendingEmailId = it }
    }

    /**
     * 表冠旋转事件处理。
     *
     * Wear OS 将表冠旋转上报为 `ACTION_SCROLL` 的 `MotionEvent`，
     * 数据源为 `SOURCE_ROTARY_ENCODER`，滚动量在 `AXIS_SCROLL` 上。
     * 这里统一转发给 [RotaryBus]，由当前活动列表消费。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta != 0f) {
                rotaryBus.onRotate(delta)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 从 Intent 中取出邮件 id（无效时返回 null） */
    private fun extractEmailId(intent: Intent?): Long? {
        val raw = intent?.getLongExtra(EXTRA_EMAIL_ID, INVALID_EMAIL_ID) ?: INVALID_EMAIL_ID
        return if (raw > 0L) raw else null
    }

    /** Android 13+ 申请通知权限；被拒绝时应用仍可用，只是没有新邮件通知 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION,
                )
            }.onFailure { Logs.w(TAG, "申请通知权限失败", it) }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 通知点击时携带的邮件 id extra（通知模块必须使用同一常量） */
        const val EXTRA_EMAIL_ID: String = "com.wm.wearmail.extra.EMAIL_ID"

        /** 无效邮件 id 哨兵值 */
        const val INVALID_EMAIL_ID: Long = -1L

        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }
}
