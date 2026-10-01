package com.wm.wearmail.notify

/**
 * 单个账户的通知开关状态（[label] 用于展示，如「工作」或邮箱地址）。
 */
data class AccountNotificationState(
    val label: String,
    val enabled: Boolean,
)

/**
 * 通知链路自检结果。
 *
 * **为什么需要它**：新邮件通知是否弹出，取决于三层开关加两个系统状态：
 * 1. 应用内「新邮件通知」总开关（[appSwitchOn]）；
 * 2. 系统是否允许本应用发通知（[osPermissionGranted]：Android 13+ 的运行时权限 + 系统总开关）；
 * 3. 「新邮件」通知渠道是否被系统关闭（[channelEnabled]）；
 * 4. 账户级开关（[accountStates]）。
 *
 * 任何一层关闭，用户看到的都只是「没有通知」四个字，无从判断原因。本类把状态显式列出，
 * 并按「最可能真正拦下通知」的顺序给出**唯一一条**可执行建议（[advice]）。
 *
 * 刻意做成**纯数据 + 纯函数**（不依赖任何 Android API），因此结论优先级可以在
 * 纯 JVM 单元测试里完整覆盖 —— 这类"用户报问题→我猜原因"的逻辑最容易写错。
 */
data class NotificationDiagnostics(
    /** 应用内总开关 */
    val appSwitchOn: Boolean,
    /** 系统允许本应用发通知（权限 + 系统开关） */
    val osPermissionGranted: Boolean,
    /** 「新邮件」渠道未被系统关闭 */
    val channelEnabled: Boolean,
    /** 各账户的通知开关 */
    val accountStates: List<AccountNotificationState> = emptyList(),
) {

    /** 三层主开关都放行，真实新邮件才会提醒 */
    val allGood: Boolean
        get() = appSwitchOn && osPermissionGranted && channelEnabled

    /** 被单独关闭通知的账户名（用于提示"这些账户不会提醒"） */
    val mutedAccounts: List<String>
        get() = accountStates.filterNot { it.enabled }.map { it.label }

    /**
     * 诊断结论（一句话，可直接展示）。
     *
     * 优先级：系统权限 → 系统渠道 → 应用内总开关 → 账户级 → 全部正常。
     * 顺序依据「杀伤力」：前两者会让系统直接吞掉通知，应用内开关只是不提醒，
     * 账户级开关只影响个别账户。
     */
    fun advice(): String = when {
        !osPermissionGranted ->
            "系统未允许本应用发送通知：请到系统「设置 → 应用 → 通知」中开启"

        !channelEnabled ->
            "系统里「新邮件」通知渠道被关闭：请到系统「设置 → 应用 → 通知」中开启该渠道"

        !appSwitchOn ->
            "应用内「新邮件通知」总开关已关闭：真实邮件不会提醒，打开上面的开关即可"

        mutedAccounts.isNotEmpty() ->
            "主开关正常；以下账户的通知被单独关闭：${mutedAccounts.joinToString("、")}"

        else ->
            "通知链路正常：真实新邮件会以「发件人 + 主题前 30 字」提醒"
    }
}
