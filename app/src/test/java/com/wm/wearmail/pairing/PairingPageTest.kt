package com.wm.wearmail.pairing

import com.wm.wearmail.model.ProviderPresets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对网页与「服务端解析」之间的**契约测试**。
 *
 * 为什么需要它：网页（[PairingPage]）由 HTML 字符串拼装，服务端（[PairingController]）
 * 按固定字段名与固定枚举名解析。两侧一旦漂移 —— 改了字段名、把 select 的 value
 * 写成中文标签、或者给主机加上 `required`（让服务端兜底失效）—— 用户端只会看到
 * 一句笼统的「提交失败」，很难定位。这里把契约钉死在测试里。
 *
 * 本测试只依赖纯字符串函数与领域模型，可在纯 JVM 运行。
 */
class PairingPageTest {

    private val html: String = PairingPage.render(
        token = "tok1234567890",
        presets = ProviderPresets.all,
        host = "192.168.1.7",
        port = 8080,
    )

    /** 取出指定 name 的 input 标签，便于做属性级断言 */
    private fun inputTag(name: String): String =
        Regex("<input[^>]*name=\"$name\"[^>]*>").find(html)?.value
            ?: error("页面里没有找到 name=$name 的 input")

    // ---------------- 字段名契约 ----------------

    @Test
    fun `页面包含服务端解析的全部字段名`() {
        // 这份清单必须与 PairingController.handleSubmit 读取的 key 完全一致
        val requiredFields = listOf(
            "t", "email", "alias", "password", "authType", "presetId",
            "imapHost", "imapPort", "imapSecurity",
            "smtpHost", "smtpPort", "smtpSecurity",
        )
        requiredFields.forEach { field ->
            assertTrue("缺少表单字段 name=\"$field\"", html.contains("name=\"$field\""))
        }
    }

    @Test
    fun `token 通过隐藏域回传`() {
        val tag = inputTag("t")
        assertTrue("token 必须是 hidden 域", tag.contains("type=\"hidden\""))
        assertTrue("token 值应写入 value", tag.contains("value=\"tok1234567890\""))
    }

    @Test
    fun `表单向 pair 路径 POST 提交`() {
        assertTrue(html.contains("action=\"/pair\""))
        assertTrue(html.contains("method=\"post\""))
    }

    // ---------------- 枚举值契约：必须是枚举名，不能是中文标签 ----------------

    @Test
    fun `认证方式的 value 使用枚举名`() {
        assertTrue(html.contains("value=\"APP_PASSWORD\""))
        assertTrue(html.contains("value=\"PASSWORD\""))
        assertTrue(html.contains("value=\"OAUTH2\""))
        assertFalse(
            "value 不能是中文标签，否则服务端只能退化为默认值",
            html.contains("value=\"应用专用密码\""),
        )
    }

    @Test
    fun `加密方式的 value 使用枚举名`() {
        assertTrue(html.contains("value=\"SSL_TLS\""))
        assertTrue(html.contains("value=\"STARTTLS\""))
        assertTrue(html.contains("value=\"NONE\""))
        assertFalse(html.contains("value=\"不加密\""))
    }

    @Test
    fun `服务商预设下拉包含全部预设 id`() {
        ProviderPresets.all.forEach { preset ->
            assertTrue(
                "预设下拉缺少 ${preset.id}",
                html.contains("<option value=\"${preset.id}\">"),
            )
        }
    }

    // ---------------- 主机可以留空（服务端兜底的前提） ----------------

    @Test
    fun `主机输入框不是必填`() {
        assertFalse(
            "IMAP 主机不能加 required：服务端要按域名兜底，前端拦住会让兜底失效",
            inputTag("imapHost").contains("required"),
        )
        assertFalse(
            "SMTP 主机不能加 required",
            inputTag("smtpHost").contains("required"),
        )
    }

    @Test
    fun `邮箱与密码仍然是必填且交给浏览器校验`() {
        assertTrue(inputTag("email").contains("required"))
        assertTrue(inputTag("password").contains("required"))
        // novalidate 会关掉浏览器的就地提示，让用户只能看到服务端的一句笼统错误
        assertFalse("不应禁用浏览器原生校验", html.contains("novalidate"))
    }

    @Test
    fun `页面说明主机可以留空`() {
        assertTrue(html.contains("可以留空"))
    }

    // ---------------- 自动填充数据与转义 ----------------

    @Test
    fun `内联脚本包含预设数据供自动填充`() {
        assertTrue(html.contains("var PRESETS = ["))
        assertTrue(html.contains("\"imapHost\""))
        assertTrue(html.contains("imap.qq.com"))
        // 填充目标 id 必须与输入框 id 一致
        listOf("imapHost", "imapPort", "imapSecurity", "smtpHost", "smtpPort", "smtpSecurity")
            .forEach { id -> assertTrue("脚本未操作 $id", html.contains("byId('$id')")) }
    }

    @Test
    fun `token 做 HTML 转义以防注入`() {
        val evil = PairingPage.render(
            token = "\"><script>alert(1)</script>",
            presets = ProviderPresets.all,
            host = "192.168.1.7",
            port = 8080,
        )
        assertFalse("token 必须以转义形式出现", evil.contains("value=\"\"><script>"))
        assertTrue(evil.contains("&quot;"))
        assertFalse(evil.contains("</script><script>alert"))
    }

    @Test
    fun `主机与端口出现在提示文案里`() {
        assertTrue(html.contains("192.168.1.7"))
        assertTrue(html.contains("8080"))
    }

    @Test
    fun `渲染结果非空且包含完整文档结构`() {
        assertNotNull(html)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("</html>"))
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
    }
}
