package com.wm.wearmail.ui.screen.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccountFormValidator] 的单元测试（纯 JVM，无需设备/Robolectric）。
 *
 * 覆盖重点：
 * - 邮箱合法/非法（无 @、无域名、带空格、中文、连续点、非法用户名、超长）；
 * - 端口边界（0 / 1 / 65535 / 65536 / "80a" / 空 / 负数 / 越界长数字）；
 * - 主机为空与规范化；
 * - 整表校验与 OAuth2 免密码分支。
 */
class AccountFormValidatorTest {

    // ------------------------------------------------------------------
    // 邮箱：合法
    // ------------------------------------------------------------------

    @Test
    fun `常见合法邮箱全部通过`() {
        val valid = listOf(
            "user@gmail.com",
            "first.last@example.com",
            "user+tag@qq.com",
            "a@b.co",
            "user_name@sub.domain.example.org",
            "user-name@my-domain.com",
            "USER@EXAMPLE.COM",
        )
        valid.forEach { email ->
            assertNull("应通过：$email", AccountFormValidator.validateEmail(email))
            assertTrue("应通过：$email", AccountFormValidator.isValidEmail(email))
        }
    }

    // ------------------------------------------------------------------
    // 邮箱：非法
    // ------------------------------------------------------------------

    @Test
    fun `缺少 at 符号判为非法`() {
        assertNotNull(AccountFormValidator.validateEmail("userexample.com"))
        assertFalse(AccountFormValidator.isValidEmail("userexample.com"))
    }

    @Test
    fun `缺少域名判为非法`() {
        // 完全没有 @ 后的域名
        assertNotNull(AccountFormValidator.validateEmail("user@"))
        // 只有一段域名（没有点），属于「域名不完整」
        val message = AccountFormValidator.validateEmail("user@localhost")
        assertNotNull(message)
        assertTrue("提示应说明域名不完整：$message", message!!.contains("域名"))
    }

    @Test
    fun `包含空格判为非法`() {
        listOf(
            "user @example.com",
            " user@example.com",
            "user@example.com ",
            "us er@example.com",
        ).forEach { email ->
            val message = AccountFormValidator.validateEmail(email)
            assertNotNull("应判为非法：[$email]", message)
            assertTrue("应提示空格问题：[$email] -> $message", message!!.contains("空格"))
        }
    }

    @Test
    fun `中文或非 ASCII 域名判为非法`() {
        listOf(
            "user@中文.com",
            "用户@example.com",
            "user@exämple.com",
        ).forEach { email ->
            val message = AccountFormValidator.validateEmail(email)
            assertNotNull("应判为非法：$email", message)
            assertTrue("应提示使用英文：$email -> $message", message!!.contains("英文"))
        }
    }

    @Test
    fun `连续点或首尾点判为非法`() {
        assertNotNull(AccountFormValidator.validateEmail("user..name@example.com"))
        assertNotNull(AccountFormValidator.validateEmail(".user@example.com"))
        assertNotNull(AccountFormValidator.validateEmail("user.@example.com"))
        assertNotNull(AccountFormValidator.validateEmail("user@example..com"))
        assertNotNull(AccountFormValidator.validateEmail("user@.example.com"))
    }

    @Test
    fun `空邮箱与超长邮箱判为非法`() {
        assertNotNull(AccountFormValidator.validateEmail(""))
        assertNotNull(AccountFormValidator.validateEmail("   "))
        val tooLong = "a".repeat(250) + "@example.com"
        assertNotNull(AccountFormValidator.validateEmail(tooLong))
    }

    @Test
    fun `多个 at 或顶级域名过短判为非法`() {
        assertNotNull(AccountFormValidator.validateEmail("a@b@example.com"))
        assertNotNull(AccountFormValidator.validateEmail("user@example.c"))
        assertNotNull(AccountFormValidator.validateEmail("user@example.123"))
    }

    // ------------------------------------------------------------------
    // 端口
    // ------------------------------------------------------------------

    @Test
    fun `端口边界值`() {
        assertFalse("0 非法", AccountFormValidator.isValidPort("0"))
        assertTrue("1 合法", AccountFormValidator.isValidPort("1"))
        assertTrue("993 合法", AccountFormValidator.isValidPort("993"))
        assertTrue("65535 合法", AccountFormValidator.isValidPort("65535"))
        assertFalse("65536 非法", AccountFormValidator.isValidPort("65536"))
        assertFalse("空串非法", AccountFormValidator.isValidPort(""))
        assertFalse("纯空格非法", AccountFormValidator.isValidPort("   "))
        assertFalse("带字母非法", AccountFormValidator.isValidPort("80a"))
        assertFalse("负数非法", AccountFormValidator.isValidPort("-1"))
        assertFalse("小数非法", AccountFormValidator.isValidPort("80.5"))
        assertFalse("超长数字非法", AccountFormValidator.isValidPort("99999999999999999999"))
        assertFalse("十六进制非法", AccountFormValidator.isValidPort("0x50"))
        assertFalse("中文数字非法", AccountFormValidator.isValidPort("９９３"))
    }

    @Test
    fun `端口允许首尾空格`() {
        assertTrue(AccountFormValidator.isValidPort(" 587 "))
    }

    // ------------------------------------------------------------------
    // 服务器
    // ------------------------------------------------------------------

    @Test
    fun `主机为空判为非法`() {
        assertNotNull(AccountFormValidator.validateServer("", "993"))
        assertNotNull(AccountFormValidator.validateServer("   ", "993"))
        assertNotNull(AccountFormValidator.validateServer("   ", ""))
        assertNull(AccountFormValidator.validateServer("imap.qq.com", "993"))
    }

    @Test
    fun `主机规范化去空格并转小写`() {
        assertEquals("imap.qq.com", AccountFormValidator.normalizeHost(" IMAP.QQ.com "))
        assertEquals("imap.qq.com", AccountFormValidator.normalizeHost("imap. qq. com"))
        assertEquals("", AccountFormValidator.normalizeHost("  "))
    }

    @Test
    fun `主机带空格但纠正后非空时服务器校验通过`() {
        // 规范化后会去掉内部空格，因此这里应通过（避免用户被无意义的空格卡住）
        assertNull(AccountFormValidator.validateServer("imap. qq.com", "993"))
    }

    // ------------------------------------------------------------------
    // 整表
    // ------------------------------------------------------------------

    @Test
    fun `正常表单通过校验`() {
        assertNull(
            AccountFormValidator.validateForm(
                email = "user@gmail.com",
                password = "app-password",
                httpHost = "imap.gmail.com",
                httpPort = "993",
                smtpHost = "smtp.gmail.com",
                smtpPort = "465",
            ),
        )
    }

    @Test
    fun `密码为空时默认判为非法`() {
        val message = AccountFormValidator.validateForm(
            email = "user@gmail.com",
            password = "",
            httpHost = "imap.gmail.com",
            httpPort = "993",
            smtpHost = "smtp.gmail.com",
            smtpPort = "465",
        )
        assertNotNull(message)
        assertTrue("提示应指向密码：$message", message!!.contains("密码"))
    }

    @Test
    fun `OAuth2 场景允许密码为空`() {
        assertNull(
            AccountFormValidator.validateForm(
                email = "user@outlook.com",
                password = "",
                httpHost = "outlook.office365.com",
                httpPort = "993",
                smtpHost = "smtp.office365.com",
                smtpPort = "587",
                requirePassword = false,
            ),
        )
    }

    @Test
    fun `整表校验的错误信息能区分 IMAP 与 SMTP`() {
        val imapMessage = AccountFormValidator.validateForm(
            email = "user@gmail.com",
            password = "pw",
            httpHost = "",
            httpPort = "993",
            smtpHost = "smtp.gmail.com",
            smtpPort = "465",
        )
        assertNotNull(imapMessage)
        assertTrue("应标注 IMAP：$imapMessage", imapMessage!!.startsWith("IMAP："))

        val smtpMessage = AccountFormValidator.validateForm(
            email = "user@gmail.com",
            password = "pw",
            httpHost = "imap.gmail.com",
            httpPort = "993",
            smtpHost = "smtp.gmail.com",
            smtpPort = "70000",
        )
        assertNotNull(smtpMessage)
        assertTrue("应标注 SMTP：$smtpMessage", smtpMessage!!.startsWith("SMTP："))
    }

    @Test
    fun `邮箱错误优先于服务器错误返回`() {
        val message = AccountFormValidator.validateForm(
            email = "bad-email",
            password = "",
            httpHost = "",
            httpPort = "0",
            smtpHost = "",
            smtpPort = "0",
        )
        assertNotNull(message)
        assertFalse("应先报邮箱问题：$message", message!!.startsWith("IMAP："))
    }
}
