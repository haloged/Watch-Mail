package com.haloged.watchmail.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.haloged.watchmail.MainActivity
import com.haloged.watchmail.R
import com.haloged.watchmail.data.local.SettingsRepository
import com.haloged.watchmail.data.repository.EmailRepository
import com.haloged.watchmail.data.repository.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 后台邮件同步Worker
 * 使用WorkManager实现定期同步
 *
 * 同步策略：
 *  - 周期任务：由设置中的"同步频率"驱动（WorkManager PeriodicWork 最小 15 分钟）
 *  - 仅在联网状态下执行
 *  - 失败按指数退避重试
 *  - 后台同步遵循系统功耗规范，不在 Worker 内做轮询
 */
class EmailSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EmailSyncWorker"
        private const val WORK_NAME = "email_sync_work"
        private const val NOTIFICATION_CHANNEL_ID = "watchmail_sync"
        private const val NOTIFICATION_ID = 1001

        /** 同步频率下限：WorkManager PeriodicWork 硬性限制为 15 分钟 */
        const val MIN_SYNC_INTERVAL_MINUTES = 15L

        /**
         * 启动/更新定期同步任务
         * 当设置中的同步频率变化后需调用本方法重新排程（REPLACE 策略）
         */
        fun enqueuePeriodicSync(context: Context, intervalMinutes: Long = MIN_SYNC_INTERVAL_MINUTES) {
            // WorkManager 最小周期为 15 分钟，低于该值会抛异常，故强制钳制
            val interval = intervalMinutes.coerceAtLeast(MIN_SYNC_INTERVAL_MINUTES)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<EmailSyncWorker>(
                interval, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            // 使用 REPLACE：设置变化后立即按新频率重排，而非保留旧任务
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )

            Log.d(TAG, "已排程定期同步任务，间隔${interval}分钟")
        }

        /**
         * 启动一次性同步任务（前台手动刷新 / 应用启动）
         */
        fun enqueueOneTimeSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)

            Log.d(TAG, "已启动一次性同步任务")
        }

        /**
         * 取消所有同步任务
         */
        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "已取消所有同步任务")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始执行后台同步")

            val repository = EmailRepository.getInstance(applicationContext)
            val settings = SettingsRepository.getInstance(applicationContext).current()

            // 创建通知渠道
            createNotificationChannel()

            // 同步所有账户
            val results = repository.syncAllAccounts()

            // 统计结果并收集新增邮件（用于逐封通知）
            var totalNewEmails = 0
            val newEmails = mutableListOf<com.haloged.watchmail.data.local.entity.EmailEntity>()
            val errors = mutableListOf<String>()

            results.forEach { (accountId, result) ->
                when (result) {
                    is SyncResult.Success -> {
                        totalNewEmails += result.newCount
                        // 仅保留"开启了通知"的账户的新邮件
                        val account = repository.getAccountById(accountId)
                        if (account == null || account.notificationEnabled) {
                            newEmails.addAll(result.newEmails)
                        }
                    }
                    is SyncResult.Error -> {
                        val account = repository.getAccountById(accountId)
                        errors.add("${account?.email ?: "未知"}: ${result.message}")
                    }
                }
            }

            // 发送新邮件通知（尊重全局通知开关 + 每账户开关）
            if (settings.notificationsEnabled && totalNewEmails > 0) {
                // 少量新邮件 → 逐封详细通知（发件人 + 主题前30字）
                // 大量新邮件 → 汇总通知，避免刷屏
                if (newEmails.size in 1..3) {
                    newEmails.forEach { email ->
                        val sender = email.fromName?.ifBlank { email.fromAddress } ?: email.fromAddress
                        NewEmailNotificationWorker.notifyNewEmail(
                            applicationContext,
                            email.id,
                            sender,
                            email.subject
                        )
                    }
                } else {
                    sendNewEmailNotification(totalNewEmails)
                }
            }

            // 发送待发送的草稿（离线草稿联网后自动补发）
            if (settings.autoSendDrafts) {
                repository.sendPendingDrafts()
            }

            Log.d(TAG, "后台同步完成: 新增${totalNewEmails}封邮件, ${errors.size}个错误")

            // 部分账户失败不影响整体结果（优雅降级，本地缓存仍可用）
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "后台同步失败", e)

            // 网络类错误交由 WorkManager 按指数退避重试
            if (isNetworkError(e)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "邮件同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台邮件同步状态"
            }

            val notificationManager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 发送汇总新邮件通知
     */
    private fun sendNewEmailNotification(count: Int) {
        if (!hasNotificationPermission()) {
            Log.w(TAG, "无通知权限，跳过通知")
            return
        }

        val notificationManager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        // 点击通知直接进入统一收件箱
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WatchMail")
            .setContentText("收到${count}封新邮件")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 检查是否已授予通知权限（API 33+ 需运行时授权）
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 判断是否为网络错误
     */
    private fun isNetworkError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("network") ||
                message.contains("connection") ||
                message.contains("timeout") ||
                message.contains("unreachable")
    }
}

/**
 * 新邮件通知Worker
 * 用于发送单封新邮件的详细通知（发件人 + 主题前30字符）
 * 点击通知直接跳转到该邮件详情页
 */
class NewEmailNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NewEmailNotificationWorker"
        private const val NOTIFICATION_CHANNEL_ID = "watchmail_new_email"
        private const val BASE_NOTIFICATION_ID = 2000

        /**
         * 发送新邮件通知
         * @param emailId 邮件本地ID，用于点击跳转
         * @param sender 发件人显示名
         * @param subject 邮件主题（展示时会截断到30字符）
         */
        fun notifyNewEmail(
            context: Context,
            emailId: Long,
            sender: String,
            subject: String
        ) {
            val data = workDataOf(
                "email_id" to emailId,
                "sender" to sender,
                "subject" to subject
            )

            val request = OneTimeWorkRequestBuilder<NewEmailNotificationWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val emailId = inputData.getLong("email_id", -1)
            val sender = inputData.getString("sender") ?: "未知发件人"
            val subject = inputData.getString("subject") ?: "(无主题)"

            if (emailId == -1L) {
                return@withContext Result.failure()
            }

            // 权限检查
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            if (!hasPermission) {
                Log.w(TAG, "无通知权限，跳过新邮件通知")
                return@withContext Result.success()
            }

            createNotificationChannel()

            val notificationManager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            // 关键：携带 email_id extra，点击通知直接跳转到该邮件详情页
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, emailId)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                emailId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 截断主题（最多30字符）
            val truncatedSubject = if (subject.length > 30) {
                subject.take(30) + "..."
            } else {
                subject
            }

            val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(sender)
                .setContentText(truncatedSubject)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 200, 100, 200)) // 振动模式
                .build()

            notificationManager.notify(
                BASE_NOTIFICATION_ID + emailId.toInt(),
                notification
            )

            Log.d(TAG, "发送新邮件通知: $sender - $truncatedSubject")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "发送通知失败", e)
            Result.failure()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "新邮件通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "新邮件到达通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }

            val notificationManager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }
    }
}
