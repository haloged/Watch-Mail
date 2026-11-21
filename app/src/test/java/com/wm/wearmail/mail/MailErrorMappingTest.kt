package com.wm.wearmail.mail

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 异常 → [MailError] 的映射（纯函数）。
 *
 * 重点守护 Outlook 的「User is authenticated but not connected.」：
 * 它必须被翻译成**可操作**的提示（启用 IMAP / 重新授权），
 * 而不是泛泛的「账号或密码/授权码错误」——那是真机日志里最有误导性的报错。
 */
class MailErrorMappingTest {

    @Test
    fun `Outlook 认证后未连接应提示启用 IMAP`() {
        val error = AuthenticationFailedExceptionWrapper(
            "User is authenticated but not connected.",
        ).toMailError()
        assertTrue("应归类为认证错误", error is MailError.Auth)
        val text = error.message.orEmpty()
        assertTrue("提示应包含 IMAP 指引：$text", text.contains("IMAP"))
        assertTrue("提示要可操作（提到启用/授权）：$text", text.contains("启用") || text.contains("授权"))
    }

    @Test
    fun `大小写与前后缀不同也能识别`() {
        val error = AuthenticationFailedExceptionWrapper(
            "[ALERT] user is authenticated but not connected. Try again.",
        ).toMailError()
        assertTrue(error is MailError.Auth)
        assertTrue(error.message.orEmpty().contains("IMAP"))
    }

    @Test
    fun `userMessage 优先展示可操作的认证原因`() {
        val error = AuthenticationFailedExceptionWrapper(
            "User is authenticated but not connected.",
        ).toMailError()
        // 不能退化成固定的「账号或密码/授权码错误」——那会把 IMAP 指引吞掉（真机反馈）
        assertTrue("应保留 IMAP 指引：${error.userMessage}", error.userMessage.contains("IMAP"))
    }

    @Test
    fun `没有原因时认证错误回退到通用文案`() {
        val error = MailError.Auth("")
        assertTrue(error.userMessage.contains("账号或密码"))
    }

    @Test
    fun `普通认证失败保留原文`() {
        val error = AuthenticationFailedExceptionWrapper("LOGIN failed: invalid credentials").toMailError()
        assertTrue(error is MailError.Auth)
        assertTrue(error.message.orEmpty().contains("LOGIN failed"))
    }
}
