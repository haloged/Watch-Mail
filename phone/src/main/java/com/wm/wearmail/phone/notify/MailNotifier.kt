package com.wm.wearmail.phone.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wm.wearmail.R
import com.wm.wearmail.phone.ui.MainActivity

/**
 * 手机通知（新邮件 / 常驻同步服务 / 测试通知）。
 *
 * 通知渠道重要性 HIGH：OPPO 等手表会**自动同步手机通知**，
 * 这正是本应用绕开「手表端通知被系统限制」的方式。
 *
 * 注意：通知里只展示「发件人 + 主题」——应用本身**不能查看邮件**，
 * 点击通知只会打开同步状态页。
 */
class MailNotifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAIL,
                "新邮件",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到新邮件时提醒（手机通知会同步到手表）"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "同步服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "常驻通知：腕邮手机正在后台收信"
                setShowBadge(false)
            },
        )
    }

    /** 新邮件通知：发件人 + 主题（+ 账户），点击打开同步状态页 */
    fun notifyNewMail(from: String, subject: String, accountEmail: String, uid: Long) {
        if (!hasPostPermission()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(from.ifBlank { "新邮件" })
            .setContentText(subject.ifBlank { "（无主题）" })
            .setSubText(accountEmail)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()
        // 通知 id 由「账户 + UID」派生：同一封邮件不会重复通知，不同邮件互不覆盖
        val id = (accountEmail + ":" + uid).hashCode()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * 测试通知：验证「通知权限 → 手机通知 → 手表同步」整条链路。
     *
     * 用途：用户在 OPPO 健康里刚开通通知权限后，可立即自测是否生效
     * （真机上「通知没到」最常见的原因是权限没开或后台被杀）。
     */
    fun notifyTest() {
        if (!hasPostPermission()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("测试通知")
            .setContentText("如果你看到这条通知，说明「腕邮手机」的通知链路正常")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification) }
    }

    /** 前台服务常驻通知（IMPORTANCE_LOW，不打扰） */
    fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("腕邮手机正在收信")
            .setContentText("新邮件会以手机通知提醒（手机通知会同步到手表）")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun hasPostPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_MAIL = "new_mail"
        const val CHANNEL_SERVICE = "sync_service"

        /** 测试通知 id（与邮件通知、常驻通知区分） */
        private const val TEST_NOTIFICATION_ID = 1002
    }
}
