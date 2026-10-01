package com.wm.wearmail.mail.oauth

import com.wm.wearmail.core.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 表单 POST 抽象。
 *
 * 抽成接口的原因：OAuth2 的**解析与状态机逻辑**要能在纯 JVM 单元测试里跑，
 * 而真实网络调用无法在测试中访问；测试注入假实现即可覆盖
 * `authorization_pending` / `slow_down` / 过期 / 拒绝等全部分支。
 */
interface FormPoster {

    /**
     * 以 `application/x-www-form-urlencoded` 提交表单，返回响应正文（UTF-8）。
     *
     * 实现约定：**HTTP 4xx/5xx 也返回正文**（OAuth2 的错误信息在 JSON 正文里），
     * 只有网络层失败或正文为空时才返回 failure。
     */
    suspend fun post(
        url: String,
        form: Map<String, String>,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Result<String>

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Int = 15_000
    }
}

/**
 * 基于 [HttpURLConnection] 的实现（不依赖任何第三方网络库）。
 *
 * 安全：日志只记录 URL 与状态码，**绝不记录表单内容**（里面含 refresh token）。
 */
class HttpFormPoster : FormPoster {

    override suspend fun post(
        url: String,
        form: Map<String, String>,
        timeoutMillis: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                // 手表端内存紧张：不要缓存响应
                setRequestProperty("Cache-Control", "no-store")
            }

            val encoded = form.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }

            try {
                connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
            } catch (t: Throwable) {
                connection.disconnect()
                throw IOException("提交 OAuth2 请求失败", t)
            }

            val status = connection.responseCode
            // 4xx 时错误信息在 errorStream 里（OAuth2 规范），必须读出来由解析层判断
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()

            Logs.d(TAG, "OAuth2 请求完成：status=$status，正文长度=${text.length}")

            if (text.isBlank()) throw IOException("OAuth2 响应为空（HTTP $status）")
            text
        }
    }

    private companion object {
        const val TAG = "OAuthHttp"
    }
}
