package com.wm.wearmail.ui.screen.accounts

/**
 * OAuth2 设备码短码的字号计算（纯函数，可单元测试）。
 *
 * 背景（真机反馈）：**438×438 圆屏**上短码用固定 `titleLarge` 会被裁切——
 * 微软的设备码可达 9 位，而界面只显示了 8 位，用户无法完成授权。
 * 这里按「可用宽度 ÷ 字符数」反推字号，让任意长度都能**完整显示**：
 * 短码较短时保持醒目（上限 26sp，与 titleLarge 一致），较长时自动缩小。
 *
 * 说明：字宽按大写字母/数字的平均比例 0.62em 估算（微软短码只含这两种字符），
 * 计算值 + letterSpacing 用于校验渲染宽度不超过可用宽度。
 */
object OAuthCodeLayout {

    /** 字号上限：短码很短时也保持与标题一致的视觉重量 */
    const val MAX_FONT_SIZE_SP: Float = 26f

    /** 字号下限：再小就看不清（微软短码 ≤ 9 位，14sp 在 438 圆屏上仍可完整放下） */
    const val MIN_FONT_SIZE_SP: Float = 14f

    /**
     * 大写字母/数字的平均字宽（em 比例）。
     *
     * 取值偏保守（0.68 而非实测均值 0.62）：**低估宽度会让字号算大 → 文本被裁切**，
     * 这正是「日志号显示不全」的直接原因（真机反馈）；宁可字号略小，也不能裁字符。
     */
    private const val GLYPH_WIDTH_EM: Float = 0.68f

    /**
     * 计算短码字号。
     *
     * @param availableWidthDp 该行实际可用宽度（圆屏弦长收窄后的宽度）
     * @param codeLength 短码字符数（微软为 8–9 位，但这里不假设上限）
     * @param letterSpacingSp 字符间距
     */
    fun fontSizeSp(
        availableWidthDp: Float,
        codeLength: Int,
        letterSpacingSp: Float,
        minFontSizeSp: Float = MIN_FONT_SIZE_SP,
    ): Float {
        if (codeLength <= 0 || availableWidthDp <= 0f) return MAX_FONT_SIZE_SP
        val perCharWidth = availableWidthDp / codeLength
        val size = (perCharWidth - letterSpacingSp) / GLYPH_WIDTH_EM
        return size.coerceIn(minFontSizeSp.coerceAtMost(MAX_FONT_SIZE_SP), MAX_FONT_SIZE_SP)
    }

    /** 按给定字号估算渲染宽度（测试用：断言不会超出可用宽度） */
    fun estimatedWidthDp(fontSizeSp: Float, codeLength: Int, letterSpacingSp: Float): Float =
        codeLength * (fontSizeSp * GLYPH_WIDTH_EM + letterSpacingSp)
}
