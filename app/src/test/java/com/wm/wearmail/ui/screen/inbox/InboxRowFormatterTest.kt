package com.wm.wearmail.ui.screen.inbox

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.MailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InboxRowFormatter] 的单元测试。
 *
 * 这些断言是「圆形表盘一行放得下多少字」这一硬约束的回归保护：
 * 截断规则一旦被改坏，列表在 466px 表盘上就会溢出或出现半个 emoji。
 */
class InboxRowFormatterTest {

    // ---------------- senderLabel ----------------

    @Test
    fun `显示名优先于邮箱地址`() {
        val from = MailAddress(address = "zhangsan@example.com", name = "张三丰")
        assertEquals("张三丰", InboxRowFormatter.senderLabel(from))
    }

    @Test
    fun `无显示名时取邮箱 at 之前的部分`() {
        val from = MailAddress(address = "zhangsan@example.com", name = null)
        assertEquals("zhangsan", InboxRowFormatter.senderLabel(from))
    }

    @Test
    fun `显示名为空白字符串时回退到邮箱`() {
        val from = MailAddress(address = "li.si@example.com", name = "   ")
        assertEquals("li.si", InboxRowFormatter.senderLabel(from))
    }

    @Test
    fun `中文显示名超长按字符数截断并加省略号`() {
        val from = MailAddress(address = "a@b.com", name = "一二三四五六七八九十甲乙丙")
        // maxChars = 14 -> 前 13 个字符 + 省略号
        val label = InboxRowFormatter.senderLabel(from, maxChars = 14)
        assertEquals("一二三四五六七八九十甲乙丙", label) // 13 个字符，正好不超
        assertEquals(13, label.codePointCount(0, label.length))
    }

    @Test
    fun `中文显示名刚好超出上限时截断`() {
        // 上限 14：给 15 个字符，期望前 13 个 + 省略号（共 14 个码点）。
        // 注意：正好等于上限时**不应**截断（能放下就完整显示），
        // 因此这里必须给「超过一个字符」的输入才符合用例名称。
        val from = MailAddress(address = "a@b.com", name = "一二三四五六七八九十甲乙丙丁戊")
        val label = InboxRowFormatter.senderLabel(from, maxChars = 14)
        assertEquals("一二三四五六七八九十甲乙丙" + InboxRowFormatter.ELLIPSIS, label)
        assertEquals(14, label.codePointCount(0, label.length))
    }

    @Test
    fun `邮箱前缀超长时按上限截断`() {
        val from = MailAddress(address = "averyverylonglocalpart@example.com", name = null)
        val label = InboxRowFormatter.senderLabel(from, maxChars = 10)
        assertEquals("averyvery" + InboxRowFormatter.ELLIPSIS, label)
        assertEquals(10, label.codePointCount(0, label.length))
    }

    @Test
    fun `emoji 不被劈成半个代理对`() {
        // 🙂 在 UTF-16 中占 2 个码元、1 个码点
        val from = MailAddress(address = "a@b.com", name = "🙂🙂🙂🙂🙂🙂")
        val label = InboxRowFormatter.senderLabel(from, maxChars = 4)
        assertEquals("🙂🙂🙂" + InboxRowFormatter.ELLIPSIS, label)
        assertEquals(4, label.codePointCount(0, label.length))
        // 不能残留孤立的高/低代理项
        assertFalse(hasLoneSurrogate(label))
    }

    /** 文本中是否存在未被配对的高/低代理项（说明代理对被劈开了） */
    private fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint in 0xD800..0xDFFF) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    @Test
    fun `发件人完全为空时回退到地址占位文本`() {
        assertEquals("(未知发件人)", InboxRowFormatter.senderLabel(MailAddress.UNKNOWN))
    }

    @Test
    fun `非法上限返回空串而不抛异常`() {
        val from = MailAddress(address = "a@b.com", name = "张三")
        assertEquals("", InboxRowFormatter.senderLabel(from, maxChars = 0))
        assertEquals("", InboxRowFormatter.senderLabel(from, maxChars = -3))
    }

    // ---------------- subjectLine ----------------

    @Test
    fun `空主题显示无主题占位`() {
        assertEquals("(无主题)", InboxRowFormatter.subjectLine(""))
        assertEquals("(无主题)", InboxRowFormatter.subjectLine("   "))
        assertEquals("(无主题)", InboxRowFormatter.subjectLine("\n\t "))
    }

    @Test
    fun `主题中的换行与多余空白被压缩`() {
        val subject = "  项目   进度\n\n汇总\t报告  "
        assertEquals("项目 进度 汇总 报告", InboxRowFormatter.subjectLine(subject))
    }

    @Test
    fun `超长主题截断并加省略号`() {
        val subject = "关于下周产品评审会议的详细议程安排与材料准备说明"
        val line = InboxRowFormatter.subjectLine(subject, maxChars = 10)
        // 9 个字符 + 省略号
        assertEquals(10, line.codePointCount(0, line.length))
        assertTrue(line.endsWith(InboxRowFormatter.ELLIPSIS))
        assertTrue(subject.startsWith(line.dropLast(1)))
    }

    @Test
    fun `主题刚好等于上限时不做截断`() {
        val subject = "一二三四五"
        assertEquals(subject, InboxRowFormatter.subjectLine(subject, maxChars = 5))
    }

    @Test
    fun `主题中的 emoji 截断保持码点完整`() {
        val line = InboxRowFormatter.subjectLine("🎉🎉🎉🎉🎉🎉", maxChars = 3)
        assertEquals("🎉🎉" + InboxRowFormatter.ELLIPSIS, line)
        assertEquals(3, line.codePointCount(0, line.length))
        assertFalse(hasLoneSurrogate(line))
    }

    // ---------------- accountFilterLabel ----------------

    private fun account(alias: String, email: String) = Account(
        id = 1L,
        email = email,
        alias = alias,
        imapHost = "imap.example.com",
        imapPort = 993,
        smtpHost = "smtp.example.com",
        smtpPort = 465,
    )

    @Test
    fun `筛选项优先使用别名`() {
        assertEquals("工作", InboxRowFormatter.accountFilterLabel(account("工作", "work@example.com")))
    }

    @Test
    fun `筛选项别名过长截断到 8 个字符`() {
        // 上限 8：给 9 个字符，期望前 7 个 + 省略号（共 8 个码点）
        val label = InboxRowFormatter.accountFilterLabel(
            account("公司主账号测试用户", "work@example.com"),
        )
        assertEquals("公司主账号测试" + InboxRowFormatter.ELLIPSIS, label)
        assertEquals(8, label.codePointCount(0, label.length))
    }

    @Test
    fun `无别名时使用邮箱 at 前部分`() {
        assertEquals(
            "work",
            InboxRowFormatter.accountFilterLabel(account("", "work@example.com")),
        )
    }

    @Test
    fun `无别名且邮箱前缀过长时截断`() {
        val label = InboxRowFormatter.accountFilterLabel(
            account("   ", "verylonglocalpart@example.com"),
        )
        assertEquals("verylon" + InboxRowFormatter.ELLIPSIS, label)
        assertEquals(8, label.codePointCount(0, label.length))
    }

    // ---------------- unreadBadgeText ----------------

    @Test
    fun `未读为零时不显示角标`() {
        assertEquals("", InboxRowFormatter.unreadBadgeText(0))
        assertEquals("", InboxRowFormatter.unreadBadgeText(-1))
    }

    @Test
    fun `未读角标边界 1 99 100`() {
        assertEquals("1", InboxRowFormatter.unreadBadgeText(1))
        assertEquals("99", InboxRowFormatter.unreadBadgeText(99))
        assertEquals("99+", InboxRowFormatter.unreadBadgeText(100))
        assertEquals("99+", InboxRowFormatter.unreadBadgeText(9999))
    }

    // ---------------- truncate ----------------

    @Test
    fun `截断的通用规则`() {
        assertEquals("abc", InboxRowFormatter.truncate("abc", 5))
        assertEquals("ab" + InboxRowFormatter.ELLIPSIS, InboxRowFormatter.truncate("abcdef", 3))
        assertEquals("", InboxRowFormatter.truncate("abc", 0))
    }
}
