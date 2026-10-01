package com.wm.wearmail.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知自检结论的单元测试。
 *
 * 为什么值得单独测：这段逻辑的作用是"用户报告收不到通知 → 告诉他是哪一层拦下的"，
 * 结论优先级写错会把人引到错误的排查方向（比不提示更糟）。
 * 纯数据 + 纯函数，因此可以在 JVM 上把全部分支钉死。
 */
class NotificationDiagnosticsTest {

    private fun diagnostics(
        appSwitchOn: Boolean = true,
        osPermissionGranted: Boolean = true,
        channelEnabled: Boolean = true,
        accounts: List<AccountNotificationState> = emptyList(),
    ) = NotificationDiagnostics(appSwitchOn, osPermissionGranted, channelEnabled, accounts)

    // ---------------- 整体判定 ----------------

    @Test
    fun `三层全开才算链路正常`() {
        assertTrue(diagnostics().allGood)
        assertFalse(diagnostics(appSwitchOn = false).allGood)
        assertFalse(diagnostics(osPermissionGranted = false).allGood)
        assertFalse(diagnostics(channelEnabled = false).allGood)
    }

    @Test
    fun `账户级开关不影响整体链路判定`() {
        // 账户级只影响个别账户：主链路仍是通的
        val onlyAccountMuted = diagnostics(
            accounts = listOf(AccountNotificationState("工作", enabled = false)),
        )
        assertTrue(onlyAccountMuted.allGood)
    }

    // ---------------- 结论优先级 ----------------

    @Test
    fun `系统权限被拒时优先提示系统设置`() {
        val advice = diagnostics(
            osPermissionGranted = false,
            channelEnabled = false,
            appSwitchOn = false,
        ).advice()

        // 三层都关时，只提示"杀伤力最大"的那一层：系统权限
        assertTrue(advice, advice.contains("系统未允许"))
        assertFalse(advice, advice.contains("总开关"))
    }

    @Test
    fun `渠道被关闭时优先级高于应用内开关`() {
        val advice = diagnostics(channelEnabled = false, appSwitchOn = false).advice()
        assertTrue(advice, advice.contains("渠道"))
    }

    @Test
    fun `仅应用内开关关闭时提示打开开关`() {
        val advice = diagnostics(appSwitchOn = false).advice()
        assertTrue(advice, advice.contains("总开关"))
        assertTrue(advice, advice.contains("真实邮件不会提醒"))
    }

    @Test
    fun `仅个别账户被关闭时列出账户名`() {
        val advice = diagnostics(
            accounts = listOf(
                AccountNotificationState("工作", enabled = false),
                AccountNotificationState("个人", enabled = true),
            ),
        ).advice()

        assertTrue(advice, advice.contains("工作"))
        assertFalse("启用的账户不应出现在提示里", advice.contains("个人"))
    }

    @Test
    fun `多个账户被关闭时全部列出`() {
        val advice = diagnostics(
            accounts = listOf(
                AccountNotificationState("工作", enabled = false),
                AccountNotificationState("个人", enabled = false),
            ),
        ).advice()

        assertTrue(advice, advice.contains("工作"))
        assertTrue(advice, advice.contains("个人"))
    }

    @Test
    fun `全部正常时说明通知内容格式`() {
        val advice = diagnostics().advice()
        assertTrue(advice, advice.contains("发件人"))
        assertTrue(advice, advice.contains("主题前 30 字"))
    }

    // ---------------- 被静音的账户列表 ----------------

    @Test
    fun `被静音账户列表只含关闭项`() {
        val diag = diagnostics(
            accounts = listOf(
                AccountNotificationState("A", enabled = true),
                AccountNotificationState("B", enabled = false),
                AccountNotificationState("C", enabled = false),
            ),
        )

        assertEquals(listOf("B", "C"), diag.mutedAccounts)
    }

    @Test
    fun `没有账户时被静音列表为空且不报错`() {
        val diag = diagnostics(accounts = emptyList())
        assertTrue(diag.mutedAccounts.isEmpty())
        assertTrue(diag.advice().contains("通知链路正常"))
    }
}
