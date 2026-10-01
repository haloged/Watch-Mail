package com.wm.wearmail.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OAuth2 令牌组的序列化与过期判断。
 *
 * 序列化结果会被加密写入 `accounts.oauth_token_enc`，因此"往返一致"是硬要求：
 * 一旦解析失败，用户会莫名要求重新授权。
 */
class OAuthTokensTest {

    private val now = 1_700_000_000_000L

    private fun tokens(
        access: String = "access-token-value",
        refresh: String? = "refresh-token-value",
        expiresAt: Long = now + 3_600_000L,
        scope: String? = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All",
    ) = OAuthTokens(access, refresh, expiresAt, scope)

    // ---------------- 序列化 ----------------

    @Test
    fun `序列化与反序列化往返一致`() {
        val original = tokens()
        val restored = OAuthTokens.parse(original.serialize())

        assertEquals(original, restored)
    }

    @Test
    fun `序列化格式为 v1 前缀且分隔符为竖线`() {
        val raw = tokens().serialize()
        assertTrue("应以 v1| 开头，实际=$raw", raw.startsWith("v1|"))
        assertEquals("应恰好 4 处分隔符", 4, raw.count { it == '|' })
    }

    @Test
    fun `没有 refresh token 也能往返`() {
        val original = tokens(refresh = null, scope = null)
        val restored = OAuthTokens.parse(original.serialize())

        assertEquals(original.accessToken, restored?.accessToken)
        assertNull(restored?.refreshToken)
        assertNull(restored?.scope)
    }

    @Test
    fun `scope 中的空格与网址不会破坏往返`() {
        val original = tokens(scope = "offline_access https://outlook.office.com/SMTP.Send")
        val restored = OAuthTokens.parse(original.serialize())

        assertEquals(original.scope, restored?.scope)
    }

    @Test
    fun `非法输入一律返回 null`() {
        assertNull(OAuthTokens.parse(null))
        assertNull(OAuthTokens.parse(""))
        assertNull(OAuthTokens.parse("   "))
        // 版本不符
        assertNull(OAuthTokens.parse("v2|a|b|1|s"))
        // 字段数量不足
        assertNull(OAuthTokens.parse("v1|a|b"))
        // access token 为空
        assertNull(OAuthTokens.parse("v1||b|1|s"))
        // 完全不是我们的格式
        assertNull(OAuthTokens.parse("乱码"))
    }

    @Test
    fun `过期时间为空或非法时按 0 处理（视为需要刷新）`() {
        val restored = OAuthTokens.parse("v1|access|refresh||")
        assertEquals(0L, restored?.expiresAtMillis)
        assertTrue("过期时间未知时必须刷新", restored?.needsRefresh(now) ?: false)

        val bad = OAuthTokens.parse("v1|access|refresh|not-a-number|")
        assertEquals(0L, bad?.expiresAtMillis)
    }

    // ---------------- 过期判断 ----------------

    @Test
    fun `剩余时间充足时不需要刷新`() {
        val fresh = tokens(expiresAt = now + 3_600_000L)
        assertFalse(fresh.needsRefresh(now))
    }

    @Test
    fun `进入提前刷新窗口后需要刷新`() {
        // 提前 2 分钟刷新：剩余 119 秒即应刷新
        val almost = tokens(expiresAt = now + 119_000L)
        assertTrue(almost.needsRefresh(now))

        val justOutside = tokens(expiresAt = now + OAuthTokens.REFRESH_SKEW_MILLIS + 1_000L)
        assertFalse(justOutside.needsRefresh(now))
    }

    @Test
    fun `已过期需要刷新`() {
        assertTrue(tokens(expiresAt = now - 1L).needsRefresh(now))
    }

    // ---------------- 可刷新性 ----------------

    @Test
    fun `只有带 refresh token 时才可刷新`() {
        assertTrue(tokens(refresh = "rt").canRefresh)
        assertFalse(tokens(refresh = null).canRefresh)
        assertFalse(tokens(refresh = "   ").canRefresh)
    }
}
