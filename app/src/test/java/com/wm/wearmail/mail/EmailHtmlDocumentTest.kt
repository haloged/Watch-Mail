package com.wm.wearmail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邮件 HTML 可渲染文档的单元测试。
 *
 * 这段逻辑是**渲染不可信内容的最后一道闸门**：WebView 已经关闭 JavaScript，
 * 但 `<meta http-equiv="refresh">` 无需 JS 就能跳转、`javascript:` 伪协议、
 * `<iframe>` 都是常见攻击面，因此清理规则必须被测试钉死。
 */
class EmailHtmlDocumentTest {

    // ---------------- 清理不可信片段 ----------------

    @Test
    fun `移除 script 标签及其内容`() {
        val cleaned = EmailHtmlDocument.sanitize("<p>正文</p><script>alert(1)</script><p>结束</p>")

        assertFalse(cleaned, cleaned.contains("script"))
        assertFalse(cleaned, cleaned.contains("alert"))
        assertTrue(cleaned, cleaned.contains("正文"))
        assertTrue(cleaned, cleaned.contains("结束"))
    }

    @Test
    fun `大小写混写的 script 同样被移除`() {
        val cleaned = EmailHtmlDocument.sanitize("<P>x</P><SCRIPT TYPE=\"text/javascript\">alert(1)</SCRIPT>")

        assertFalse(cleaned.lowercase(), cleaned.lowercase().contains("alert"))
        assertTrue(cleaned, cleaned.contains("x"))
    }

    @Test
    fun `移除 iframe（外部内容载体）`() {
        val cleaned = EmailHtmlDocument.sanitize("<iframe src=\"https://evil.example\"></iframe><p>正文</p>")

        assertFalse(cleaned, cleaned.contains("iframe"))
        assertFalse(cleaned, cleaned.contains("evil.example"))
        assertTrue(cleaned, cleaned.contains("正文"))
    }

    @Test
    fun `移除 meta refresh（无需 JS 即可跳转）`() {
        val cleaned = EmailHtmlDocument.sanitize(
            "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\"><p>正文</p>",
        )

        assertFalse(cleaned.lowercase(), cleaned.contains("refresh"))
        assertFalse(cleaned, cleaned.contains("evil.example"))
    }

    @Test
    fun `javascript 伪协议被替换为 blocked`() {
        val cleaned = EmailHtmlDocument.sanitize("<a href=\"javascript:alert(1)\">点我</a>")

        assertFalse(cleaned.lowercase(), cleaned.contains("javascript:"))
        assertTrue(cleaned, cleaned.contains("blocked:"))
        assertTrue("链接文字应保留", cleaned.contains("点我"))
    }

    @Test
    fun `正常邮件内容不被误删`() {
        val html = "<div><h1>标题</h1><p>你好</p><img src=\"https://cdn.example/a.png\"></div>"

        val cleaned = EmailHtmlDocument.sanitize(html)

        assertEquals(html, cleaned)
    }

    // ---------------- 文档包装与适配 ----------------

    @Test
    fun `生成完整文档并注入圆屏适配样式`() {
        val doc = EmailHtmlDocument.build("<p>你好</p>")

        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("</html>"))
        assertTrue(doc.contains("charset=\"utf-8\""))
        assertTrue("必须注入 viewport 否则会横向溢出", doc.contains("width=device-width"))
        assertTrue(doc.contains("color-scheme:dark"))
        assertTrue("深色背景下要保证对比度", doc.contains("background:#000"))
        assertTrue("图片不得超出容器宽度", doc.contains("max-width:100%!important"))
        assertTrue("正文应保留", doc.contains("<p>你好</p>"))
    }

    @Test
    fun `内边距按 466px 圆屏的固定占用给出`() {
        val doc = EmailHtmlDocument.build("<p>x</p>")

        // 上 78 让出返回按钮与顶部圆弧，下 92 让出底部操作栏
        assertTrue(doc, doc.contains("padding:78px 20px 92px"))
    }

    @Test
    fun `默认屏蔽远程图片`() {
        val doc = EmailHtmlDocument.build("<img src=\"https://tracker.example/pixel.gif\">")

        assertTrue("默认应隐藏 http(s) 图片", doc.contains("img[src^=\"http\"]{display:none}"))
    }

    @Test
    fun `允许远程图片时不再隐藏`() {
        val doc = EmailHtmlDocument.build(
            "<img src=\"https://cdn.example/a.png\">",
            allowRemoteImages = true,
        )

        assertFalse(doc, doc.contains("display:none"))
        // 图片仍受宽度约束
        assertTrue(doc.contains("max-width:100%!important"))
    }

    // ---------------- 上限与可渲染判定 ----------------

    @Test
    fun `超过上限的 HTML 判定为不可保留`() {
        val ok = "a".repeat(EmailHtmlDocument.MAX_HTML_CHARS)
        val tooLong = "a".repeat(EmailHtmlDocument.MAX_HTML_CHARS + 1)

        assertFalse(EmailHtmlDocument.exceedsLimit(ok))
        assertTrue(EmailHtmlDocument.exceedsLimit(tooLong))
        assertFalse("null 视为没有 HTML", EmailHtmlDocument.exceedsLimit(null))
    }

    @Test
    fun `清理阶段也会截断超长输入`() {
        val huge = "<p>" + "a".repeat(EmailHtmlDocument.MAX_HTML_CHARS * 2) + "</p>"

        val cleaned = EmailHtmlDocument.sanitize(huge)

        assertTrue(cleaned.length <= EmailHtmlDocument.MAX_HTML_CHARS)
    }

    @Test
    fun `可渲染判定需要设置开启且有 HTML`() {
        assertTrue(EmailHtmlDocument.isRenderable(enabled = true, html = "<p>x</p>"))
        assertFalse("设置关闭时即使用 HTML 也不渲染", EmailHtmlDocument.isRenderable(enabled = false, html = "<p>x</p>"))
        assertFalse(EmailHtmlDocument.isRenderable(enabled = true, html = null))
        assertFalse(EmailHtmlDocument.isRenderable(enabled = true, html = ""))
        assertFalse(EmailHtmlDocument.isRenderable(enabled = true, html = "   "))
    }
}
