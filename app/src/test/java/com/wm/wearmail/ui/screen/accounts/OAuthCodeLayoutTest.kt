package com.wm.wearmail.ui.screen.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OAuth2 短码字号计算（纯函数）的单元测试。
 *
 * 守护点（真机反馈：438×438 圆屏上短码只显示 8 位）：
 * - 9 位短码在 438 屏的窄可用宽度下必须**完整显示**（估算宽度 ≤ 可用宽度）；
 * - 短码较短时保持醒目（上限 26sp）；
 * - 极长时不低于可读下限 14sp。
 */
class OAuthCodeLayoutTest {

    private val spacing = 1f

    /** 438×438 圆屏中部、弦长收窄后某行的保守可用宽度 */
    private val width438 = 150f

    /** 466×466 圆屏的对应可用宽度 */
    private val width466 = 190f

    @Test
    fun `438 屏 9 位短码完整显示`() {
        val size = OAuthCodeLayout.fontSizeSp(width438, 9, spacing)
        val rendered = OAuthCodeLayout.estimatedWidthDp(size, 9, spacing)
        assertTrue("字号应在可读范围内：$size", size >= OAuthCodeLayout.MIN_FONT_SIZE_SP)
        assertTrue("9 位短码不应超出可用宽度：$rendered > $width438", rendered <= width438 + 0.5f)
    }

    @Test
    fun `更大屏幕给出更大字号但不超过上限`() {
        val small = OAuthCodeLayout.fontSizeSp(width438, 9, spacing)
        val large = OAuthCodeLayout.fontSizeSp(width466, 9, spacing)
        assertTrue("466 屏字号不应小于 438 屏", large >= small)
        assertTrue(large <= OAuthCodeLayout.MAX_FONT_SIZE_SP)
    }

    @Test
    fun `长度增加时字号下降且不超宽`() {
        val size8 = OAuthCodeLayout.fontSizeSp(width438, 8, spacing)
        val size12 = OAuthCodeLayout.fontSizeSp(width438, 12, spacing)
        assertTrue("12 位应比 8 位字号小", size12 <= size8)
        val rendered = OAuthCodeLayout.estimatedWidthDp(size12, 12, spacing)
        assertTrue("12 位不应超出可用宽度", rendered <= width438 + 0.5f)
    }

    @Test
    fun `极短短码保持视觉上限`() {
        assertEquals(OAuthCodeLayout.MAX_FONT_SIZE_SP, OAuthCodeLayout.fontSizeSp(width438, 4, spacing), 0.01f)
    }

    @Test
    fun `日志号 13 字符在窄行也能完整显示`() {
        // 反馈中心最窄的行约 130dp：13 字符应能放下（字号可低于 OAuth 短码的 14sp 下限）
        val width = 130f
        val size = OAuthCodeLayout.fontSizeSp(width, 13, spacing, minFontSizeSp = 10f)
        val rendered = OAuthCodeLayout.estimatedWidthDp(size, 13, spacing)
        assertTrue("字号不应低于 10sp：$size", size >= 10f)
        assertTrue("13 字符应完整显示：$rendered > $width", rendered <= width + 0.5f)
    }

    @Test
    fun `非法输入回退到上限字号`() {
        assertEquals(OAuthCodeLayout.MAX_FONT_SIZE_SP, OAuthCodeLayout.fontSizeSp(0f, 9, spacing), 0.01f)
        assertEquals(OAuthCodeLayout.MAX_FONT_SIZE_SP, OAuthCodeLayout.fontSizeSp(width438, 0, spacing), 0.01f)
    }
}
