package com.wm.wearmail.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对表单「服务器地址兜底」的单元测试。
 *
 * 回归背景：主机留空曾被直接判为校验失败，用户只看到一句「提交失败」。
 * 这里保证：能按域名/预设识别的，一律自动补齐；识别不出来的才允许报错。
 */
class PairingFormDefaultsTest {

    // ---------------- 表单填全：完全尊重用户输入 ----------------

    @Test
    fun `表单填了主机时原样采用`() {
        val resolved = PairingFormDefaults.resolve(
            email = "me@corp.example.com",
            formImapHost = "imap.corp.example.com",
            formSmtpHost = "smtp.corp.example.com",
            formPresetId = "enterprise",
        )

        assertEquals("imap.corp.example.com", resolved?.imapHost)
        assertEquals("smtp.corp.example.com", resolved?.smtpHost)
        assertEquals("enterprise", resolved?.presetId)
        assertFalse("填全了不应标记为兜底", resolved?.usedFallback ?: true)
    }

    @Test
    fun `主机两侧空白会被裁剪`() {
        val resolved = PairingFormDefaults.resolve(
            email = "me@qq.com",
            formImapHost = "  imap.qq.com  ",
            formSmtpHost = "\tsmtp.qq.com\n",
        )

        assertEquals("imap.qq.com", resolved?.imapHost)
        assertEquals("smtp.qq.com", resolved?.smtpHost)
        assertFalse(resolved?.usedFallback ?: true)
    }

    // ---------------- 主机留空：按域名兜底 ----------------

    @Test
    fun `主机留空时按域名回填 Gmail`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@gmail.com",
            formImapHost = "",
            formSmtpHost = "",
        )

        assertEquals("imap.gmail.com", resolved?.imapHost)
        assertEquals("smtp.gmail.com", resolved?.smtpHost)
        assertEquals("gmail", resolved?.presetId)
        assertTrue(resolved?.usedFallback ?: false)
    }

    @Test
    fun `主机留空时按域名回填 QQ 邮箱`() {
        val resolved = PairingFormDefaults.resolve(
            email = "12345@qq.com",
            formImapHost = "",
            formSmtpHost = "",
        )

        assertEquals("imap.qq.com", resolved?.imapHost)
        assertEquals("smtp.qq.com", resolved?.smtpHost)
        assertEquals("qq", resolved?.presetId)
    }

    @Test
    fun `主机留空时按域名回填 163 邮箱`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@163.com",
            formImapHost = "",
            formSmtpHost = "",
        )

        assertEquals("imap.163.com", resolved?.imapHost)
        assertEquals("smtp.163.com", resolved?.smtpHost)
        assertEquals("netease", resolved?.presetId)
    }

    @Test
    fun `主机留空时按域名回填 Outlook`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@outlook.com",
            formImapHost = "",
            formSmtpHost = "",
        )

        assertEquals("outlook.office365.com", resolved?.imapHost)
        assertEquals("smtp.office365.com", resolved?.smtpHost)
        assertEquals("outlook", resolved?.presetId)
    }

    @Test
    fun `子域名同样可以识别`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@mail.qq.com",
            formImapHost = "",
            formSmtpHost = "",
        )

        assertEquals("qq", resolved?.presetId)
    }

    // ---------------- 只填一侧：用预设补齐另一侧 ----------------

    @Test
    fun `只填了 IMAP 主机时用预设补齐 SMTP`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@qq.com",
            formImapHost = "imap.mycompany.com",
            formSmtpHost = "",
        )

        assertEquals("imap.mycompany.com", resolved?.imapHost)
        assertEquals("smtp.qq.com", resolved?.smtpHost)
        assertTrue(resolved?.usedFallback ?: false)
    }

    @Test
    fun `只填了 SMTP 主机时用预设补齐 IMAP`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@qq.com",
            formImapHost = "   ",
            formSmtpHost = "smtp.mycompany.com",
        )

        assertEquals("imap.qq.com", resolved?.imapHost)
        assertEquals("smtp.mycompany.com", resolved?.smtpHost)
        assertTrue("纯空白应视为未填写", resolved?.usedFallback ?: false)
    }

    // ---------------- 显式选择优先于域名识别 ----------------

    @Test
    fun `显式选择的服务商优先于域名识别`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@qq.com",
            formImapHost = "",
            formSmtpHost = "",
            formPresetId = "gmail",
        )

        assertEquals("imap.gmail.com", resolved?.imapHost)
        assertEquals("smtp.gmail.com", resolved?.smtpHost)
        assertEquals("gmail", resolved?.presetId)
    }

    @Test
    fun `选中的预设不可用时会退回域名识别`() {
        // enterprise 预设没有主机地址，不能作为兜底来源
        val resolved = PairingFormDefaults.resolve(
            email = "someone@qq.com",
            formImapHost = "",
            formSmtpHost = "",
            formPresetId = "enterprise",
        )

        assertEquals("qq", resolved?.presetId)
        assertEquals("imap.qq.com", resolved?.imapHost)
    }

    // ---------------- 无法识别：返回 null，由调用方给出明确提示 ----------------

    @Test
    fun `企业邮箱且未填主机时返回 null`() {
        val resolved = PairingFormDefaults.resolve(
            email = "me@corp.example.com",
            formImapHost = "",
            formSmtpHost = "",
        )

        assertNull(resolved)
    }

    @Test
    fun `邮箱为空且未填主机时返回 null`() {
        assertNull(
            PairingFormDefaults.resolve(
                email = "",
                formImapHost = "",
                formSmtpHost = "",
            ),
        )
    }

    @Test
    fun `预设 id 为空白字符串时按未选择处理`() {
        val resolved = PairingFormDefaults.resolve(
            email = "someone@qq.com",
            formImapHost = "",
            formSmtpHost = "",
            formPresetId = "   ",
        )

        assertEquals("qq", resolved?.presetId)
    }
}
