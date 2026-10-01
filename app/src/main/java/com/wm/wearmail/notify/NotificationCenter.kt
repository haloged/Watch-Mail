package com.wm.wearmail.notify

import android.Manifest
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
import com.wm.wearmail.MainActivity
import com.wm.wearmail.R
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.EmailMeta

/**
 * 新邮件通知实现（[MailNotifier] 的唯一实现）。
 *
 * 需求（2.5）落地方式：
 * - 通知内容 = 发件人（最多 24 字符）+ 主题前 [MailNotifier.SUBJECT_MAX_CHARS] 字符；
 * - 振动提醒（渠道振动 + 通知自带振动模式），抬腕即可看到；
 * - 点击通知通过 [MainActivity.EXTRA_EMAIL_ID] 直接进入该邮件详情页；
 * - 三级开关：全局开关 → 账户级开关（SettingsStore）→ 账户对象自身开关，
 *   任一为 false 都不弹通知。
 *
 * 安全/稳定性：
 * - 通知标题与正文都做长度截断，避免超长文本刷屏或撑爆通知栏；
 * - 未授予 POST_NOTIFICATIONS 时静默返回，绝不抛异常影响同步流程。
 */
class NotificationCenter(private val container: AppContainer) : MailNotifier {

    private val context: Context
        get() = container.app

    override fun ensureChannels() {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager == null) {
                Logs.w(TAG, "通知服务不可用，跳过渠道创建")
                return
            }

            // 新邮件：高优先级 + 振动，确保抬腕可见
            val newMailChannel = NotificationChannel(
                MailNotifier.CHANNEL_NEW_MAIL,
                CHANNEL_NEW_MAIL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到新邮件时振动提醒"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                setShowBadge(true)
            }

            // 同步状态：低频、不打扰（同步失败等提示走这里）
            val syncChannel = NotificationChannel(
                MailNotifier.CHANNEL_SYNC,
                CHANNEL_SYNC_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台同步状态提示"
                enableVibration(false)
                setShowBadge(false)
            }

            // createNotificationChannels 幂等：同 id 重复创建只更新配置
            manager.createNotificationChannels(listOf(newMailChannel, syncChannel))
        } catch (t: Throwable) {
            Logs.w(TAG, "创建通知渠道失败", t)
        }
    }

    override fun notifyNewMail(account: Account, mail: EmailMeta) {
        try {
            // 三级开关：全局 → 账户级（设置持久化）→ 账户对象
            if (!container.settings.settings.value.notificationsEnabled) return
            if (!container.settings.accountNotifications(account.id)) return
            if (!account.notificationsEnabled) return
            if (!hasNotificationPermission()) {
                Logs.d(TAG, "未授予通知权限，跳过新邮件通知")
                return
            }

            post(
                title = mail.from.display.take(FROM_MAX_CHARS),
                text = mail.subjectOrPlaceholder.take(MailNotifier.SUBJECT_MAX_CHARS),
                // 用邮件 id 作为通知 id：同一封邮件重复通知时覆盖而不是堆叠
                notificationId = mail.id.toInt(),
                emailId = mail.id,
                groupKey = groupKey(account.id),
            )
        } catch (security: SecurityException) {
            Logs.w(TAG, "缺少通知权限，新邮件通知被系统拒绝", security)
        } catch (t: Throwable) {
            Logs.w(TAG, "发送新邮件通知失败", t)
        }
    }

    /**
     * 通知链路自检（见 [NotificationDiagnostics] 的说明）。
     *
     * 任何一处读取失败都不抛异常：自检本身绝不能成为新的崩溃源，
     * 读不到的值按"不可用"处理并如实展示。
     */
    override fun diagnose(): NotificationDiagnostics {
        val systemEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)

        return NotificationDiagnostics(
            appSwitchOn = runCatching {
                container.settings.settings.value.notificationsEnabled
            }.getOrDefault(false),
            osPermissionGranted = hasNotificationPermission() && systemEnabled,
            channelEnabled = isNewMailChannelEnabled(),
            accountStates = runCatching {
                container.accounts.accounts.value.map { account ->
                    AccountNotificationState(
                        label = account.displayLabel,
                        // 账户级开关有两处来源，任一为 false 都不提醒
                        enabled = container.settings.accountNotifications(account.id) &&
                            account.notificationsEnabled,
                    )
                }
            }.getOrDefault(emptyList()),
        )
    }

    override fun notifyTest(): Boolean = try {
        // 渠道可能因应用刚安装/清数据而尚未创建
        ensureChannels()

        val systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!hasNotificationPermission() || !systemEnabled) {
            Logs.w(TAG, "测试通知未发出：系统未允许本应用发送通知")
            false
        } else {
            // 内容与真实邮件同样受长度限制约束，因此看到的排版与真实通知一致
            post(
                title = TEST_TITLE.take(FROM_MAX_CHARS),
                text = TEST_TEXT.take(MailNotifier.SUBJECT_MAX_CHARS),
                notificationId = TEST_NOTIFICATION_ID,
                // 不带邮件 id：点击只打开应用首页（MainActivity 会把无效 id 视为 null）
                emailId = null,
                groupKey = null,
            )
            Logs.i(TAG, "已发出测试通知")
            true
        }
    } catch (security: SecurityException) {
        Logs.w(TAG, "测试通知被系统拒绝", security)
        false
    } catch (t: Throwable) {
        Logs.w(TAG, "发送测试通知失败", t)
        false
    }

    /**
     * 统一的通知投递。
     *
     * 真实邮件与测试通知走这里，保证两者**渠道/振动/优先级/点击路径完全一致** ——
     * 否则「测试通过但真邮件不响」就成了新的坑。
     */
    private fun post(
        title: String,
        text: String,
        notificationId: Int,
        emailId: Long?,
        groupKey: String?,
    ) {
        val builder = NotificationCompat.Builder(context, MailNotifier.CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(contentIntent(emailId))

        if (groupKey != null) builder.setGroup(groupKey)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    /** 「新邮件」渠道是否被用户在系统设置里关闭（渠道尚未创建时按可用处理） */
    private fun isNewMailChannelEnabled(): Boolean = try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channel = manager?.getNotificationChannel(MailNotifier.CHANNEL_NEW_MAIL)
        channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    } catch (t: Throwable) {
        Logs.w(TAG, "读取通知渠道状态失败，按可用处理", t)
        true
    }

    override fun cancelAll() {
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (security: SecurityException) {
            Logs.w(TAG, "清除通知被系统拒绝", security)
        } catch (t: Throwable) {
            Logs.w(TAG, "清除通知失败", t)
        }
    }

    /** 点击通知直达邮件详情页；requestCode 用邮件 id，保证不同邮件不会被复用 */
    private fun contentIntent(emailId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // 测试通知不带邮件 id：MainActivity 会把无效 id 视为 null，只打开首页
        if (emailId != null) intent.putExtra(MainActivity.EXTRA_EMAIL_ID, emailId)
        return PendingIntent.getActivity(
            context,
            emailId?.toInt() ?: TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 是否已获得通知权限。
     *
     * Android 13（TIRAMISU）起 POST_NOTIFICATIONS 才是运行时权限；
     * 更低版本（含多数 Wear OS 3/4 设备）无需检查，否则会被误判为「未授权」而静默丢弃所有通知。
     */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Logs.w(TAG, "通知权限检查失败，按未授权处理", t)
            false
        }
    }

    /** 通知分组键：按账户分组，便于后续增加「账户汇总」通知 */
    private fun groupKey(accountId: Long): String = "$GROUP_PREFIX$accountId"

    companion object {
        private const val TAG = "NotificationCenter"

        private const val CHANNEL_NEW_MAIL_NAME = "新邮件"
        private const val CHANNEL_SYNC_NAME = "同步状态"

        /** 通知标题（发件人）最大展示字符数 */
        private const val FROM_MAX_CHARS = 24

        /**
         * 测试通知的固定通知 id。
         *
         * 取值远离邮件表自增 id（从 1 开始），避免测试通知覆盖真实邮件通知、
         * 或反过来被真实通知覆盖。
         */
        private const val TEST_NOTIFICATION_ID = 999_000_001

        /** 测试通知的 PendingIntent requestCode（与邮件 id 不冲突） */
        private const val TEST_REQUEST_CODE = 999_000_001

        /** 测试通知文案：刻意控制在长度限制以内，排版与真实通知一致 */
        private const val TEST_TITLE = "通知测试"

        private const val TEST_TEXT = "这是一条测试通知，收到即表示通知功能正常"

        private const val GROUP_PREFIX = "wearmail_account_"

        /** 振动节奏：立即 → 60ms → 停 60ms → 60ms */
        private val VIBRATION_PATTERN = longArrayOf(0, 60, 60, 60)
    }
}
