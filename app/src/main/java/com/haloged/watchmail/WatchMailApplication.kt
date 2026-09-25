package com.haloged.watchmail

import android.app.Application
import android.util.Log
import com.haloged.watchmail.data.local.SettingsRepository
import com.haloged.watchmail.service.EmailSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * WatchMail Application类
 * 负责初始化应用组件
 */
class WatchMailApplication : Application() {

    companion object {
        private const val TAG = "WatchMailApplication"
    }

    /** 应用级协程作用域（避免泄露 Activity 生命周期） */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "WatchMail应用启动")

        // 启动后台同步服务
        initializeBackgroundSync()
    }

    /**
     * 初始化后台同步
     * 同步频率由用户设置驱动（DataStore 持久化）
     */
    private fun initializeBackgroundSync() {
        try {
            applicationScope.launch {
                // 读取用户设置的同步频率，未设置则用默认 15 分钟
                val settings = SettingsRepository.getInstance(this@WatchMailApplication)
                    .current()

                // 排程周期同步（WorkManager PeriodicWork 最小 15 分钟）
                EmailSyncWorker.enqueuePeriodicSync(
                    this@WatchMailApplication,
                    settings.syncFrequencyMinutes.toLong()
                )

                // 立即执行一次同步
                EmailSyncWorker.enqueueOneTimeSync(this@WatchMailApplication)

                Log.d(TAG, "后台同步初始化完成，频率=${settings.syncFrequencyMinutes}分钟")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化后台同步失败", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "WatchMail应用终止")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "内存不足警告")
    }
}
