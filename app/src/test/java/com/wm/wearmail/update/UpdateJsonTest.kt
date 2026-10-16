package com.wm.wearmail.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub Releases API 响应解析的单元测试。
 *
 * 测试使用真实的 org.json 实现（`testImplementation(libs.org.json)`），
 * 覆盖字段缺失、null body、非法 JSON 等真实会遇到的情况 ——
 * 解析失败必须安全返回 null，绝不能把异常抛给网络层。
 */
class UpdateJsonTest {

    @Test
    fun `解析完整响应`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/haloged/Watch-Mail/releases/tag/v1.2.0",
              "body": "修复若干问题。",
              "published_at": "2026-10-10T12:00:00Z"
            }
        """.trimIndent()
        val info = UpdateJson.parseLatestRelease(json)
        assertEquals("v1.2.0", info?.tagName)
        assertEquals("1.2.0", info?.version)
        assertEquals("https://github.com/haloged/Watch-Mail/releases/tag/v1.2.0", info?.releaseUrl)
        assertEquals("修复若干问题。", info?.notes)
    }

    @Test
    fun `tag 无 v 前缀时原样保留`() {
        val json = """{"tag_name":"1.2.0","html_url":"https://example.com/r"}"""
        val info = UpdateJson.parseLatestRelease(json)
        assertEquals("1.2.0", info?.version)
        assertEquals("1.2.0", info?.tagName)
    }

    @Test
    fun `body 为 null 时说明为空串`() {
        val json = """{"tag_name":"v1.2.0","html_url":"https://example.com/r","body":null}"""
        val info = UpdateJson.parseLatestRelease(json)
        assertEquals("", info?.notes)
    }

    @Test
    fun `缺少必需字段返回 null`() {
        // 缺 html_url
        assertNull(UpdateJson.parseLatestRelease("""{"tag_name":"v1.2.0"}"""))
        // 缺 tag_name
        assertNull(UpdateJson.parseLatestRelease("""{"html_url":"https://example.com/r"}"""))
    }

    @Test
    fun `字段为空白字符串返回 null`() {
        assertNull(
            UpdateJson.parseLatestRelease("""{"tag_name":"  ","html_url":"https://example.com/r"}"""),
        )
        assertNull(
            UpdateJson.parseLatestRelease("""{"tag_name":"v1.2.0","html_url":""}"""),
        )
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(UpdateJson.parseLatestRelease("not json"))
        assertNull(UpdateJson.parseLatestRelease(""))
        // JSON 数组不是对象
        assertNull(UpdateJson.parseLatestRelease("[]"))
    }

    @Test
    fun `解析 assets 时优先取 release 命名的 APK`() {
        val json = """
            {
              "tag_name": "v1.2.2",
              "html_url": "https://github.com/haloged/Watch-Mail/releases/tag/v1.2.2",
              "assets": [
                {"name": "app-debug.apk", "browser_download_url": "https://github.com/haloged/Watch-Mail/releases/download/v1.2.2/app-debug.apk"},
                {"name": "app-release.apk", "browser_download_url": "https://github.com/haloged/Watch-Mail/releases/download/v1.2.2/app-release.apk"},
                {"name": "source.zip", "browser_download_url": "https://github.com/haloged/Watch-Mail/releases/download/v1.2.2/source.zip"}
              ]
            }
        """.trimIndent()
        val info = UpdateJson.parseLatestRelease(json)
        assertEquals(
            "https://github.com/haloged/Watch-Mail/releases/download/v1.2.2/app-release.apk",
            info?.apkUrl,
        )
    }

    @Test
    fun `assets 无 APK 时下载地址为 null`() {
        val noApk = """
            {"tag_name":"v1.2.2","html_url":"https://example.com/r",
             "assets":[{"name":"src.zip","browser_download_url":"https://example.com/src.zip"}]}
        """.trimIndent()
        assertNull(UpdateJson.parseLatestRelease(noApk)?.apkUrl)
        // 完全没有 assets 字段（旧版本发布也可能不带资产）
        val noAssets = """{"tag_name":"v1.2.2","html_url":"https://example.com/r"}"""
        assertNull(UpdateJson.parseLatestRelease(noAssets)?.apkUrl)
    }

    @Test
    fun `无 release 命名时回退第一个 APK 且拒绝非 https`() {
        val fallback = """
            {"tag_name":"v1.2.2","html_url":"https://example.com/r",
             "assets":[{"name":"WearMail.apk","browser_download_url":"https://example.com/WearMail.apk"}]}
        """.trimIndent()
        assertEquals("https://example.com/WearMail.apk", UpdateJson.parseLatestRelease(fallback)?.apkUrl)

        // 非 https 的下载地址不可用（避免被篡改的响应把安装包指向明文地址）
        val insecure = """
            {"tag_name":"v1.2.2","html_url":"https://example.com/r",
             "assets":[{"name":"WearMail.apk","browser_download_url":"http://example.com/WearMail.apk"}]}
        """.trimIndent()
        assertNull(UpdateJson.parseLatestRelease(insecure)?.apkUrl)
    }
}
