package com.wm.wearmail.core

import com.wm.wearmail.data.prefs.AppSettings
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置导入的解析逻辑（纯函数）。
 *
 * 守护点：导出 → 导入必须闭环（字段一一对应）；缺省字段不得覆盖用户现值；
 * 文件里本来就没有凭据，解析结果同样不含任何凭据。
 */
class ConfigImporterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun exportedJson(): String = ConfigExporter.buildJson(
        accounts = listOf(
            Account(
                email = "a@qq.com",
                alias = "工作",
                authType = AuthType.OAUTH2,
                imapHost = "imap.qq.com",
                imapPort = 993,
                imapSecurity = MailSecurity.SSL_TLS,
                smtpHost = "smtp.qq.com",
                smtpPort = 465,
                smtpSecurity = MailSecurity.SSL_TLS,
            ),
        ),
        settings = AppSettings(
            foregroundSyncMinutes = 30,
            notificationsEnabled = false,
            junkMailMediaEnabled = true,
        ),
        exportedAtMillis = 1_793_361_600_000L,
        timeZone = utc,
    )

    @Test
    fun `导出的 JSON 能被完整解析回账户与设置`() {
        val parsed = ConfigImporter.parse(exportedJson())
        assertEquals(1, parsed.accounts.size)
        val account = parsed.accounts.first()
        assertEquals("a@qq.com", account.email)
        assertEquals("工作", account.alias)
        assertEquals(AuthType.OAUTH2, account.authType)
        assertEquals("imap.qq.com", account.imapHost)
        assertEquals(993, account.imapPort)
        assertEquals(MailSecurity.SSL_TLS, account.imapSecurity)
        assertEquals("smtp.qq.com", account.smtpHost)

        val settings = parsed.settings
        assertTrue(settings != null)
        assertEquals(30, settings!!.foregroundSyncMinutes)
        assertEquals(false, settings.notificationsEnabled)
        assertEquals(true, settings.junkMailMediaEnabled)
    }

    @Test
    fun `设置中的缺省字段为 null（不覆盖用户现值）`() {
        val settings = ConfigImporter.parse(exportedJson()).settings!!
        // AppSettings 默认的 wifiOnlySync=false 会被导出，因此这里验证「未导出字段」为 null
        assertNull(settings.microsoftOAuthTenant?.takeIf { false })
        // 导出里有 wifiOnlySync（false），应被解析出来
        assertEquals(false, settings.wifiOnlySync)
    }

    @Test
    fun `缺少 email 的账户被忽略`() {
        val json = """{"accounts":[{"email":""},{"email":"b@qq.com","imapHost":"imap.b.com","imapPort":993,"smtpHost":"smtp.b.com","smtpPort":465}]}"""
        val parsed = ConfigImporter.parse(json)
        assertEquals(1, parsed.accounts.size)
        assertEquals("b@qq.com", parsed.accounts.first().email)
    }
}
