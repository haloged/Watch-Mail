package com.wm.wearmail.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.SyncReason
import kotlin.coroutines.cancellation.CancellationException

/**
 * 后台周期同步任务（WorkManager）。
 *
 * 约束（由 [SyncEngine.scheduleBackgroundSync] 设置）：仅在网络连通时执行，
 * 周期不低于 15 分钟以符合系统功耗策略。
 *
 * 注意事项：
 * - Worker 运行在**后台线程**，绝不能触碰 UI / Compose；
 * - 依赖通过 [AppContainer.from] 获取，与前台共享同一个进程内容器，
 *   因此同步结果会立刻反映到 UI 上；
 * - 失败返回 [Result.retry]，由 WorkManager 按退避策略重试；
 *   认证/配置类错误也会重试，但不会造成额外负担（同步很快就失败返回）。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val container = AppContainer.from(applicationContext)
            val report = container.sync.syncAll(SyncReason.BACKGROUND)
            Logs.i(
                TAG,
                "后台同步完成：账户=${report.results.size} 成功=${report.successCount} " +
                    "新增=${report.newMailCount} 耗时=${report.durationMillis}ms",
            )
            Result.success()
        } catch (cancel: CancellationException) {
            // 任务被系统取消时不要吞掉取消信号
            throw cancel
        } catch (t: Throwable) {
            Logs.e(TAG, "后台同步失败，交由 WorkManager 重试", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
