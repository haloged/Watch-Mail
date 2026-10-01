package com.wm.wearmail.notify

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.EmailMeta

/**
 * 新邮件通知门面。
 *
 * 通知设计要求（需求 2.5）：
 * - 内容为「发件人 + 主题前 30 字符」；
 * - 振动 + 抬腕亮屏提示；
 * - 点击通知直接进入该邮件详情页（通过 Intent extra 传递邮件 id）；
 * - 支持按账户关闭通知（账户级开关由 [com.wm.wearmail.data.prefs.SettingsStore] 持久化）。
 */
interface MailNotifier {

    /** 创建通知渠道（应用启动时调用一次即可，重复调用幂等） */
    fun ensureChannels()

    /**
     * 弹出新邮件通知。
     *
     * 实现须自行检查全局与账户级通知开关，关闭时直接返回。
     */
    fun notifyNewMail(account: Account, mail: EmailMeta)

    /** 清除所有本应用通知（例如用户点击「全部已读」后） */
    fun cancelAll()

    /**
     * 通知链路自检：三层开关 + 系统权限/渠道状态。
     *
     * 供设置页展示，让用户在"收不到通知"时能直接看到是哪一层拦下的。
     */
    fun diagnose(): NotificationDiagnostics

    /**
     * 发出测试通知，返回是否成功提交给系统。
     *
     * 与真实新邮件走**完全相同**的投递路径（渠道、振动、优先级、点击行为），
     * 因此可用来验证通知链路；点击后只打开应用首页（不带邮件 id）。
     * 有意**绕过应用内总开关**：否则开关一关「测试」就什么也不做，
     * 用户无法区分"系统不允许"与"应用内被关掉"。
     */
    fun notifyTest(): Boolean

    companion object {
        /** 新邮件通知渠道 id */
        const val CHANNEL_NEW_MAIL: String = "wearmail_new_mail"

        /** 同步失败等低频通知渠道 */
        const val CHANNEL_SYNC: String = "wearmail_sync"

        /** 主题在通知中的最大展示字符数 */
        const val SUBJECT_MAX_CHARS: Int = 30
    }
}
