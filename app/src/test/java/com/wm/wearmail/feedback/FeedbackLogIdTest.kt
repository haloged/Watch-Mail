package com.wm.wearmail.feedback

import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈日志号与维格表请求体的单元测试（纯函数）。
 *
 * 守护点：
 * - 日志号格式固定 `WM-yyyyMMdd-HHmmss-XXX`（用户按它查日志，格式不能漂移）；
 * - 创建记录的 JSON 必须写入用户给的**字段 ID**（日志号 / 附件），
 *   附件为 `[{name,size,mimeType,token}]` 结构（Vika 官方要求）。
 */
class FeedbackLogIdTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `日志号格式固定`() {
        val logId = FeedbackLogId.generate(now = 1_793_361_263_123L, random = 7, timeZone = utc)
        // 13 字符（月日-时分-序号）：手表圆屏上单行可完整显示
        assertEquals("1030-1154-007", logId)
        assertTrue(Regex("""\d{4}-\d{4}-\d{3}""").matches(logId))
        assertEquals(13, logId.length)
    }

    @Test
    fun `随机序号越界时夹紧到三位`() {
        val logId = FeedbackLogId.generate(now = 1_793_361_263_123L, random = 5000, timeZone = utc)
        assertTrue(logId.endsWith("-999"))
    }

    @Test
    fun `创建记录的 JSON 使用字段 ID 且附件结构正确`() {
        val json = JSONObject(
            VikaPayload.recordJson(
                logId = "1030-1154-007",
                attachmentToken = "space/2026/10/30/abc",
                fileName = "wearmail-2026-10-30.log",
                size = 2048L,
            ),
        )
        val record = json.getJSONArray("records").getJSONObject(0)
        val fields = record.getJSONObject("fields")

        // 写入必须用**字段名**（用字段 ID 会被 Vika 拒绝：400 参数格式不正确）
        assertEquals("1030-1154-007", fields.getString(VikaUploader.FIELD_LOG_ID_NAME))
        assertEquals("日志号", VikaUploader.FIELD_LOG_ID_NAME)
        assertEquals("fld7Ntc8wrQjT", VikaUploader.FIELD_LOG_ID)

        val attachment = fields.getJSONArray(VikaUploader.FIELD_LOG_FILE_NAME).getJSONObject(0)
        assertEquals("space/2026/10/30/abc", attachment.getString("token"))
        assertEquals("wearmail-2026-10-30.log", attachment.getString("name"))
        assertEquals(2048L, attachment.getLong("size"))
        assertEquals("text/plain", attachment.getString("mimeType"))
        assertEquals("日志文件", VikaUploader.FIELD_LOG_FILE_NAME)
        assertEquals("fld47e6jowGPx", VikaUploader.FIELD_LOG_FILE)
    }
}
