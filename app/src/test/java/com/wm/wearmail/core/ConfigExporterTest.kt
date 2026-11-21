package com.wm.wearmail.core

import com.wm.wearmail.data.prefs.AppSettings
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置导出（开发测试功能）的单元测试。
 *
 * 守护点（安全红线）：
 * - 导出内容**绝不能包含任何凭据**（密码/授权码/令牌）；
 * - OAuth 客户端 ID 只导出「是否配置」，签名只导出长度；
 * - 账户的服务器参数完整，便于复现问题。
 */
class ConfigExporterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private val accounts = listOf(
        Account(
            email = "a@qq.com",
            alias = "工作",
            authType = AuthType.APP_PASSWORD,
            imapHost = "imap.qq.com",
            imapPort = 993,
            imapSecurity = MailSecurity.SSL_TLS,
            smtpHost = "smtp.qq.com",
            smtpPort = 465,
            smtpSecurity = MailSecurity.SSL_TLS,
        ),
        Account(
            email = "x@outlook.com",
            authType = AuthType.OAUTH2,
            imapHost = "outlook.office365.com",
            imapPort = 993,
            smtpHost = "smtp.office365.com",
            smtpPort = 587,
            smtpSecurity = MailSecurity.STARTTLS,
        ),
    )

    @Test
    fun `导出包含账户服务器参数与开关项`() {
        val json = JSONObject(
            ConfigExporter.buildJson(
                accounts = accounts,
                settings = AppSettings(),
                exportedAtMillis = 1_793_361_600_000L,
                timeZone = utc,
            ),
        )
        assertEquals("wearmail-config", json.getString("format"))
        assertEquals(2, json.getJSONArray("accounts").length())
        val first = json.getJSONArray("accounts").getJSONObject(0)
        assertEquals("a@qq.com", first.getString("email"))
        assertEquals("imap.qq.com", first.getString("imapHost"))
        assertEquals(993, first.getInt("imapPort"))
        assertTrue(json.getJSONObject("settings").has("foregroundSyncMinutes"))
    }

    @Test
    fun `导出绝不包含凭据`() {
        val raw = ConfigExporter.buildJson(accounts, AppSettings(), 1_793_361_600_000L, utc)
        val lower = raw.lowercase()
        // 注意用「带引号的字段名」匹配：authType 的枚举值 APP_PASSWORD 含有 password 字样，
        // 但它只是认证方式名称，不是凭据（裸词匹配会误报）
        listOf(
            "\"password\"",
            "\"token\"",
            "\"oauthaccesstoken\"",
            "\"refresh_token\"",
            "\"secret\"",
            "\"authorization\"",
        ).forEach { needle ->
            assertFalse("导出内容不应包含凭据字段 $needle", lower.contains(needle))
        }
        // 客户端 ID 只导出是否配置
        val settings = JSONObject(raw).getJSONObject("settings")
        assertTrue(settings.has("microsoftOAuthClientIdConfigured"))
        assertTrue(settings.has("signatureLength"))
        assertFalse(settings.has("microsoftOAuthClientId"))
    }

    @Test
    fun `导出的时间戳按本地时区格式化为秒级字符串`() {
        val json = JSONObject(ConfigExporter.buildJson(emptyList(), AppSettings(), 1_793_361_600_000L, utc))
        assertEquals("2026-10-30 12:00:00", json.getString("exportedAt"))
        assertEquals(0, json.getJSONArray("accounts").length())
    }
}
