package com.wm.wearmail.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HtmlTextExtractor] 单元测试（纯 JVM，不依赖 android.text）。
 */
class HtmlTextExtractorTest {

    private fun plain(html: String): String = HtmlTextExtractor.toPlainText(html)

    // ---------------- 标签剥离 ----------------

    @Test
    fun `剥离标签保留文本`() {
        assertEquals("Hello", plain("<div><span>Hello</span></div>"))
        assertEquals("你好 世界", plain("<p>你好 <b>世界</b></p>"))
    }

    @Test
    fun `标签大小写不敏感`() {
        assertEquals("A", plain("<DIV>A</DIV>"))
        assertEquals("B", plain("<span CLASS=\"x\">B</SPAN>"))
    }

    @Test
    fun `未闭合的尖括号不会被误删`() {
        assertEquals("3 < 5 且 7 > 2", plain("3 < 5 且 7 > 2"))
    }

    // ---------------- 换行转换 ----------------

    @Test
    fun `br 转换为换行`() {
        assertEquals("第一行\n第二行", plain("第一行<br>第二行"))
        assertEquals("第一行\n第二行", plain("第一行<br/>第二行"))
        assertEquals("第一行\n第二行", plain("第一行<br />第二行"))
    }

    @Test
    fun `段落转换为空行`() {
        assertEquals("第一段\n\n第二段", plain("<p>第一段</p><p>第二段</p>"))
    }

    @Test
    fun `div 与 li 转换为换行`() {
        assertEquals("上\n下", plain("<div>上<br>下</div>"))
        // 相邻块级标签各自产生一次换行，两个换行会被压缩为「最多一个空行」
        assertEquals("上\n\n下", plain("<div>上</div><div>下</div>"))
        assertEquals("一\n\n二", plain("<ul><li>一</li><li>二</li></ul>"))
    }

    @Test
    fun `表格行为转换为同一行文本`() {
        assertEquals("a b", plain("<table><tr><td>a</td><td>b</td></tr></table>"))
    }

    // ---------------- 非正文内容 ----------------

    @Test
    fun `script 内容被移除`() {
        assertEquals("保留", plain("<script>var a = 1;</script>保留"))
        assertEquals("保留", plain("<SCRIPT>alert(1)</SCRIPT>保留"))
        assertEquals("保留", plain("<script type=\"text/javascript\">x</script>保留"))
    }

    @Test
    fun `style 内容被移除`() {
        assertEquals("保留", plain("<style>.a { color: red; }</style>保留"))
        assertEquals("保留", plain("<style type=\"text/css\">p{}</style>保留"))
    }

    @Test
    fun `注释被移除`() {
        assertEquals("前后", plain("前<!-- 这里是注释 -->后"))
        assertEquals("前后", plain("前<!--[if IE]>x<![endif]-->后"))
    }

    @Test
    fun `DOCTYPE 声明被移除`() {
        assertEquals("正文", plain("<!DOCTYPE html><html><body>正文</body></html>"))
    }

    // ---------------- 实体解码 ----------------

    @Test
    fun `命名实体被解码`() {
        assertEquals(
            "a & b <c> \"d\" 'e' 'f'",
            plain("a &amp; b &lt;c&gt; &quot;d&quot; &#39;e&#39; &apos;f&apos;"),
        )
    }

    @Test
    fun `数字实体被解码`() {
        assertEquals("AB", plain("&#65;&#x42;"))
        assertEquals("A", plain("&#x41;"))
    }

    @Test
    fun `四位与五位码位实体被解码`() {
        // U+1F600 位于 BMP 之外，必须生成代理对
        assertEquals("\uD83D\uDE00", plain("&#x1F600;"))
        assertEquals("\uD83D\uDE00", plain("&#128512;"))
    }

    @Test
    fun `nbsp 被转换为普通空格`() {
        assertEquals("a b", plain("a&nbsp;b"))
        assertEquals("前", plain("&nbsp;&nbsp;前"))
    }

    @Test
    fun `未知实体保持原样`() {
        assertEquals("&unknown; 文本", plain("&unknown; 文本"))
    }

    @Test
    fun `实体解码发生在标签剥离之后`() {
        // &lt;b&gt; 解码后是字面量 <b>，不能被当成标签删掉
        assertEquals("<b>", plain("&lt;b&gt;"))
    }

    // ---------------- 空白归一化 ----------------

    @Test
    fun `行内多余空白被压缩`() {
        assertEquals("a b", plain("a   \t  b"))
        assertEquals("a b", plain("  a\t\tb  "))
    }

    @Test
    fun `连续空行压缩为最多一个空行`() {
        assertEquals("a\n\nb", plain("a<br><br><br>b"))
        assertEquals("前\n\n后", plain("前<p></p><p></p>后"))
    }

    @Test
    fun `纯空白内容返回空串`() {
        assertEquals("", plain("<p>&nbsp;</p><p>&nbsp;</p>"))
        assertEquals("", plain("   \n\t  "))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", plain(""))
    }

    @Test
    fun `完整 HTML 邮件`() {
        val html = """
            <html>
              <head><style>body{font:12px}</style></head>
              <body>
                <!-- 邮件正文 -->
                <p>你好，张三：</p>
                <p>本周的会议改到 <b>周三 14:00</b>。<br>请确认。</p>
                <script>track()</script>
                <div>&nbsp;</div>
                <div>此致</div>
              </body>
            </html>
        """.trimIndent()

        assertEquals(
            "你好，张三：\n\n本周的会议改到 周三 14:00。\n请确认。\n\n此致",
            plain(html),
        )
    }
}
