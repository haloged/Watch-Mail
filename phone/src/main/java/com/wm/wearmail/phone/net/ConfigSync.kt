package com.wm.wearmail.phone.net

import com.wm.wearmail.core.Logs
import com.wm.wearmail.phone.data.PairingEndpoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从手表拉取账户配置（局域网 HTTP，一次性 token 会话内）。
 *
 * 与手表端 `PairingController` 的 `GET /export?t=<token>` 对应；
 * 返回的 JSON 含明文凭据 —— 调用方必须立即加密落盘，不得写日志。
 */
object ConfigSync {

    private const val TAG = "ConfigSync"
    private const val TIMEOUT_MS = 8_000

    suspend fun fetchConfig(endpoint: PairingEndpoint): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(endpoint.exportUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IOException("HTTP $code")
                    }
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    runCatching { connection.disconnect() }
                }
            }.onFailure { t ->
                // 只记异常类型：排查「同步失败」时看日志即可区分网络/明文拦截/token 失效
                Logs.w(TAG, "拉取配置失败：${t.javaClass.simpleName}")
            }
        }
}
