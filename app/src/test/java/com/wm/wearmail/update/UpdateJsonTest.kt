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
}
