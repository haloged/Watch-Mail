package com.wm.wearmail.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内下载策略的单元测试（纯函数部分）。
 *
 * 覆盖：加速候选生成（gh-proxy 前缀）、速度过慢判定（滑动窗口阈值）、
 * 进度百分比（含总大小未知的 -1 不确定态）、Release 说明清洗。
 */
class ApkDownloaderTest {

    // ---------------- DownloadUrls：加速候选 ----------------

    @Test
    fun `github 链接生成直连与代理两个候选`() {
        val url = "https://github.com/haloged/Watch-Mail/releases/download/v1.2.2/app-release.apk"
        assertEquals(
            listOf(url, "https://gh-proxy.com/$url"),
            DownloadUrls.candidates(url),
        )
    }

    @Test
    fun `非 github 链接不生成代理候选`() {
        val url = "https://example.com/app.apk"
        assertEquals(listOf(url), DownloadUrls.candidates(url))
        assertFalse(DownloadUrls.isAccelerable(url))
    }

    // ---------------- DownloadPolicy：速度判定 ----------------

    @Test
    fun `窗口未满时不判过慢（连接与首包延迟不计入）`() {
        assertFalse(DownloadPolicy.isTooSlow(bytesInWindow = 0, windowMs = 5_000))
        assertFalse(DownloadPolicy.isTooSlow(bytesInWindow = 0, windowMs = 5_999))
    }

    @Test
    fun `窗口内速度低于阈值判过慢`() {
        // 6 秒只下了 100KB（约 17KB/s < 24KB/s）
        assertTrue(DownloadPolicy.isTooSlow(bytesInWindow = 100_000, windowMs = 6_000))
    }

    @Test
    fun `窗口内速度正常不判过慢`() {
        // 6 秒 300KB（50KB/s）
        assertFalse(DownloadPolicy.isTooSlow(bytesInWindow = 300_000, windowMs = 6_000))
        // 恰好处在阈值（24KB/s × 6s = 144000B）不算过慢（用 < 而非 <=）
        assertFalse(DownloadPolicy.isTooSlow(bytesInWindow = 144_000, windowMs = 6_000))
    }

    // ---------------- DownloadProgress：百分比 ----------------

    @Test
    fun `百分比计算与未知总大小`() {
        assertEquals(50, DownloadProgress(50, 100, viaProxy = false).percent)
        assertEquals(100, DownloadProgress(90, 90, viaProxy = false).percent)
        // 总大小未知（服务端未给 Content-Length）
        assertEquals(-1, DownloadProgress(50, -1, viaProxy = false).percent)
        // 防御：下载字节超总数（服务端给了错误长度）时不越界
        assertEquals(100, DownloadProgress(120, 100, viaProxy = false).percent)
    }

    // ---------------- ReleaseNotes：更新日志清洗 ----------------

    @Test
    fun `清洗 Markdown 标记保留可读文本`() {
        val raw = "### 新增\n- **下载**直连\n- 修复崩溃\n\n\n更多"
        assertEquals(
            "新增\n· 下载直连\n· 修复崩溃\n\n更多",
            ReleaseNotes.toDisplayText(raw),
        )
    }

    @Test
    fun `空白说明返回空串且不 panic`() {
        assertEquals("", ReleaseNotes.toDisplayText(""))
        assertEquals("", ReleaseNotes.toDisplayText("   \n\t  "))
    }

    @Test
    fun `超长说明截断并加省略号`() {
        val raw = "A".repeat(3_000)
        val out = ReleaseNotes.toDisplayText(raw, maxChars = 100)
        assertEquals(101, out.length) // 100 + 省略号
        assertTrue(out.endsWith("…"))
    }
}
