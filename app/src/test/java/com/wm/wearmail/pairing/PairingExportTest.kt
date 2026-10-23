package com.wm.wearmail.pairing

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.OAuthTokens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「同步到手机」配置导出的单元测试（纯函数，无网络）。
 *
 * 守护点：字段名与取值的稳定（手机端按同一套 key 解析）、
 * OAuth2 账户带令牌组且不带密码、空列表与缺省 clientId 的边界。
 */
class PairingExportTest {

    private fun account(email: String = "a@qq.com") = Account(
        email = email,
        imapHost = "imap.qq.com",
        imapPort = 993,
        smtpHost = "smtp.qq.com",
        smtpPort = 465,
        authType = AuthType.APP_PASSWORD,
        imapSecurity = MailSecurity.SSL_TLS,
        smtpSecurity = MailSecurity.SSL_TLS,
    )

    @Test
    fun `导出包含版本账户字段与明文密码`() {
        val json = PairingExport.toJson(
            listOf(ExportAccount(account(), AccountSecrets(password = "授权码123"))),
            oauthClientId = "client-id-1",
        )
        val root = JSONObject(json)
        assertEquals(PairingExport.FORMAT_VERSION, root.getInt("version"))
        assertEquals("client-id-1", root.getString("oauthClientId"))

        val item = root.getJSONArray("accounts").getJSONObject(0)
        assertEquals("a@qq.com", item.getString("email"))
        assertEquals("imap.qq.com", item.getString("imapHost"))
        assertEquals(993, item.getInt("imapPort"))
        assertEquals("SSL_TLS", item.getString("imapSecurity"))
        assertEquals("smtp.qq.com", item.getString("smtpHost"))
        assertEquals(465, item.getInt("smtpPort"))
        assertEquals("APP_PASSWORD", item.getString("authType"))
        assertEquals("授权码123", item.getString("password"))
        assertFalse(item.has("oauthAccessToken"))
    }

    @Test
    fun `OAuth2 账户导出令牌组且不带密码`() {
        val tokens = OAuthTokens(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMillis = 123L,
            scope = "scope1",
        )
        val json = PairingExport.toJson(
            listOf(ExportAccount(account("x@outlook.com"), AccountSecrets(oauth = tokens))),
            oauthClientId = null,
        )
        val item = JSONObject(json).getJSONArray("accounts").getJSONObject(0)
        assertFalse(item.has("password"))
        assertEquals("access", item.getString("oauthAccessToken"))
        assertEquals("refresh", item.getString("oauthRefreshToken"))
        assertEquals(123L, item.getLong("oauthExpiresAt"))
        assertEquals("scope1", item.getString("oauthScope"))
    }

    @Test
    fun `空账户列表导出合法空结构且空白 clientId 被忽略`() {
        val root = JSONObject(PairingExport.toJson(emptyList(), oauthClientId = "  "))
        assertEquals(0, root.getJSONArray("accounts").length())
        assertFalse(root.has("oauthClientId"))
    }
}
