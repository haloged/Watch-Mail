package com.wm.wearmail.feedback

import android.content.Context
import com.wm.wearmail.BuildConfig
import com.wm.wearmail.core.LogFileWriter
import com.wm.wearmail.core.Logs
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 反馈上传的业务异常（message 直接展示给用户） */
class FeedbackException(message: String) : Exception(message)

/**
 * 「上传日志」：把**当天日志**上传到维格表（Vika）反馈表。
 *
 * 两步（Vika 官方 API，见 https://developers.vika.cn/api/reference）：
 * 1. `POST /fusion/v1/datasheets/{id}/attachments`（multipart，字段名 `file`）→ 附件 token；
 * 2. `POST /fusion/v1/datasheets/{id}/records?fieldKey=id` → 新建一行：
 *    日志号写入 [FIELD_LOG_ID]，附件写入 [FIELD_LOG_FILE]。
 *
 * 日志号在上传**前**生成（`WM-yyyyMMdd-HHmmss-XXX`），成功后展示给用户，
 * 用户把它填进 GitHub Issue / 飞书表单，开发者即可在表里按号查到日志。
 *
 * ⚠️ API Token 内置于代码（用户提供的反馈表专用令牌）：公开仓库可见；
 * 该令牌只能向这张反馈表写入，不要复用到其他资产。
 */
object VikaUploader {

    private const val TAG = "VikaUploader"
    private const val BASE = "https://vika.cn/fusion/v1"

    /**
     * 反馈表上传令牌：由构建注入（`gradle.properties` 的 `wearmail.vikaToken`），
     * **源码里不出现真值**——公开仓库的构建默认没有令牌（上传日志会提示未配置）。
     */
    private val API_TOKEN: String = BuildConfig.VIKA_API_TOKEN

    private const val DATASHEET_ID = "dstEViprm90v2rKLK9"

    /**
     * 写入用的字段**名**：Vika 的 `fieldKey=id` 只影响读取，创建记录时用字段 ID 会返回
     * 400「传入的 fields 参数格式不正确」（真机实测），因此写入一律用字段名。
     */
    const val FIELD_LOG_ID_NAME = "日志号"

    /** 「日志文件」附件字段名（同上：写入必须用名称） */
    const val FIELD_LOG_FILE_NAME = "日志文件"

    /** 字段 ID（仅作与表结构对照用，**不可用于写入**） */
    const val FIELD_LOG_ID = "fld7Ntc8wrQjT"

    /** 「日志文件」字段 ID（仅作与表结构对照用，**不可用于写入**） */
    const val FIELD_LOG_FILE = "fld47e6jowGPx"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MIME_TEXT = "text/plain"

    /**
     * 上传当天日志。
     *
     * @return 成功时为日志号（展示给用户）；失败时为可读的中文原因
     */
    suspend fun uploadLog(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val file = LogFileWriter.todayFile()
        if (file == null || !file.exists() || file.length() == 0L) {
            return@withContext Result.failure(
                FeedbackException("今天还没有日志：先正常使用一会儿（收发邮件、同步）再来上传"),
            )
        }
        if (API_TOKEN.isBlank()) {
            return@withContext Result.failure(
                FeedbackException("当前版本未配置反馈服务令牌，无法上传日志；可改用 GitHub / 表单反馈"),
            )
        }
        val logId = FeedbackLogId.generate()
        runCatching {
            val token = uploadAttachment(file)
            createRecord(logId, token, file.name, file.length())
            Logs.i(TAG, "日志已上传，日志号=$logId")
            logId
        }.recoverCatching { t ->
            Logs.w(TAG, "上传日志失败：${t.javaClass.simpleName}")
            throw FeedbackException(readableMessage(t))
        }
    }

    // ------------------------------------------------------------------
    // 第一步：上传附件
    // ------------------------------------------------------------------

    private fun uploadAttachment(file: File): String {
        val boundary = "----WearMail" + UUID.randomUUID().toString().replace("-", "")
        val connection = openConnection("$BASE/datasheets/$DATASHEET_ID/attachments").apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            val head = StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(file.name).append("\"\r\n")
                .append("Content-Type: ").append(MIME_TEXT).append("; charset=utf-8\r\n\r\n")
                .toString()
                .toByteArray(Charsets.UTF_8)
            val content = file.readBytes()
            val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            // 显式长度（不用 chunked）：与创建记录同理，避免网关不解析请求体
            connection.setFixedLengthStreamingMode(head.size + content.size + tail.size)
            connection.outputStream.use { out ->
                out.write(head)
                out.write(content)
                out.write(tail)
                out.flush()
            }
            val json = readJson(connection)
            if (!json.optBoolean("success")) {
                throw IOException("附件上传失败：${json.optString("message")}")
            }
            return json.optJSONObject("data")?.optString("token").orEmpty().ifBlank {
                throw IOException("附件上传未返回 token")
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ------------------------------------------------------------------
    // 第二步：创建记录（日志号 + 附件）
    // ------------------------------------------------------------------

    private fun createRecord(logId: String, token: String, fileName: String, size: Long) {
        // 注意：不能用 ?fieldKey=id —— 实测 Vika 的「创建记录」只接受**字段名**作 key，
        // 用字段 ID 会返回 400「传入的 fields 参数格式不正确」
        val connection = openConnection("$BASE/datasheets/$DATASHEET_ID/records").apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val bodyBytes = VikaPayload.recordJson(logId, token, fileName, size).toByteArray(Charsets.UTF_8)
            // 显式长度：避免以 chunked 方式发送（部分网关不解析 chunked 请求体，会写入空记录）
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { it.write(bodyBytes) }
            val json = readJson(connection)
            if (!json.optBoolean("success")) {
                throw IOException("记录创建失败：${json.optString("message")}")
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $API_TOKEN")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException("HTTP $code")
        }
        return runCatching { JSONObject(body) }.getOrElse {
            throw IOException("响应格式异常")
        }
    }

    private fun readableMessage(t: Throwable): String {
        if (t is FeedbackException) return t.message.orEmpty()
        if (t is UnknownHostException) return "无法连接维格表：请检查网络后重试"
        if (t is SocketTimeoutException) return "上传超时：请稍后重试"
        val raw = t.message.orEmpty()
        return when {
            raw.startsWith("HTTP 400") -> "上传失败：请求被反馈服务拒绝（字段格式不符）$raw"
            raw.startsWith("HTTP 401") -> "上传失败：反馈服务令牌无效"
            raw.startsWith("HTTP 403") -> "上传失败：反馈表用量已达上限"
            raw.startsWith("HTTP 413") || raw.startsWith("HTTP 426") -> "上传失败：日志文件过大"
            raw.startsWith("HTTP 429") -> "上传失败：操作太频繁，请稍后重试"
            raw.startsWith("HTTP 5") -> "上传失败：反馈服务暂时不可用（$raw）"
            // 我们自己构造的可读原因（附件/记录创建失败、无 token 等）原样透传，
            // 不再退化成只有类名的「上传失败（IOException）」（真机排障反馈）
            raw.isNotBlank() -> "上传失败：$raw"
            else -> "上传失败（${t.javaClass.simpleName}）：请稍后重试"
        }
    }
}

/**
 * 维格表「创建记录」的请求体构造（纯函数，可单元测试）。
 *
 * 结构（官方文档）：
 * ```json
 * {"records":[{"fields":{"<日志号字段>":"WM-...","<附件字段>":[{"name":"...","size":N,"mimeType":"text/plain","token":"space/..."}]}}]}
 * ```
 */
object VikaPayload {

    fun recordJson(
        logId: String,
        attachmentToken: String,
        fileName: String,
        size: Long,
        mimeType: String = "text/plain",
    ): String {
        val attachment = JSONObject().apply {
            put("name", fileName)
            put("size", size)
            put("mimeType", mimeType)
            put("token", attachmentToken)
        }
        val fields = JSONObject().apply {
            put(VikaUploader.FIELD_LOG_ID_NAME, logId)
            put(VikaUploader.FIELD_LOG_FILE_NAME, JSONArray().put(attachment))
        }
        val record = JSONObject().apply { put("fields", fields) }
        return JSONObject().put("records", JSONArray().put(record)).toString()
    }
}
