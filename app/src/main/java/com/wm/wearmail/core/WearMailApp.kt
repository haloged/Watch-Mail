package com.wm.wearmail.core

import android.app.Application
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 冷启动时序（目标 < 2 秒）：
 * 1. 构造依赖容器（全部 `by lazy`，此处几乎不做事）；
 * 2. 创建通知渠道（纯本地、微秒级）；
 * 3. 注册后台周期同步（WorkManager 内部异步，不阻塞主线程）；
 * 4. 异步预热账户列表，UI 首帧先渲染空态再用数据刷新。
 *
 * 这里**不做**任何网络请求：IMAP 连接全部交给 [com.wm.wearmail.sync.SyncService]，
 * 避免冷启动被网络超时（10 秒）拖垮。
 */
class WearMailApp : Application() {

    /** 进程内唯一的依赖容器 */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()

        val container = this.container

        // 通知渠道：必须在任何通知发出前创建（Android 8.0+ 要求）
        runCatching { container.notifications.ensureChannels() }
            .onFailure { Logs.e(TAG, "创建通知渠道失败", it) }

        // 后台同步：遵循系统限制，周期不低于 15 分钟
        runCatching { container.sync.scheduleBackgroundSync() }
            .onFailure { Logs.e(TAG, "注册后台同步失败", it) }

        // 异步预热账户列表，让统一收件箱尽早拿到账户信息
        container.scope.launch {
            runCatching { container.accounts.load() }
                .onFailure { Logs.e(TAG, "预热账户列表失败", it) }
        }

        Logs.i(TAG, "WearMail 应用启动完成")
    }

    companion object {
        private const val TAG = "WearMailApp"
    }
}
