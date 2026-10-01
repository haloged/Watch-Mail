package com.wm.wearmail.pairing

import com.wm.wearmail.core.Logs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 极简本地 HTTP 服务（扫码配对用）。
 *
 * 为什么手写而不是用 NanoHTTPD/Ktor：
 * - 只需要「静态页面 + 一个表单提交」，引入框架会让 APK 增加数百 KB；
 * - 手写实现的所有代码路径都可审查（这是唯一对局域网开放的服务，安全边界必须清晰）。
 *
 * **重要约束：本文件不允许 import 任何 `android.*`**，
 * 这样它可以在纯 JVM 单元测试里真实启动并被请求（见 `ConfigWebServerTest`）。
 *
 * 线程模型：
 * - 1 个 accept 线程（accept 带 soTimeout，用于周期性检查停止标志）；
 * - 固定 2 个 worker 线程处理请求（手机端逐个提交表单，2 个足够）。
 *
 * 协议支持：HTTP/1.1 的 GET 与 POST（`application/x-www-form-urlencoded`），
 * 响应一律 `Connection: close`，即一次请求一条连接，避免长连接状态管理的复杂度。
 */
class ConfigWebServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) {

    /**
     * 已解析的请求。
     *
     * @param query URL 上的查询参数（已做 URL 解码）；POST 表单字段也会合并进来，
     *              因此 handler 可以统一用 `query["t"]` 取到一次性 token，
     *              原始表单文本仍保留在 [body] 中。
     * @param headers 请求头，键统一转为小写
     * @param body 原始请求体文本（UTF-8 解码）
     */
    data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    /** 待发送的响应；[body] 为已编码好的字节，避免二次转换 */
    data class HttpResponse(
        val status: Int = 200,
        val contentType: String = "text/html; charset=utf-8",
        val body: ByteArray = ByteArray(0),
    ) {
        // data class 自动生成的 equals 对 ByteArray 用的是引用比较，
        // 这里改成内容比较，便于测试与调试时直接断言响应。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HttpResponse) return false
            return status == other.status &&
                contentType == other.contentType &&
                body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + contentType.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }

        companion object {
            /** HTML 响应（表单页/结果页） */
            fun html(body: String, status: Int = 200): HttpResponse = HttpResponse(
                status = status,
                contentType = "text/html; charset=utf-8",
                body = body.toByteArray(StandardCharsets.UTF_8),
            )

            /** 纯文本响应（错误提示） */
            fun text(body: String, status: Int = 200): HttpResponse = HttpResponse(
                status = status,
                contentType = "text/plain; charset=utf-8",
                body = body.toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** 实际监听端口（构造时传 0 表示由系统分配） */
    @Volatile
    private var actualPort: Int = -1

    private val running = AtomicBoolean(false)

    /** 服务是否正在监听 */
    val isRunning: Boolean
        get() = running.get()

    /** 实际监听端口；未启动过（或启动失败）时为 -1，运行结束后保留最后一次的端口便于排障 */
    val boundPort: Int
        get() = actualPort

    /**
     * 启动监听。
     *
     * @return 绑定成功返回 true；端口被占用等失败情况返回 false（不抛异常，便于调用方换端口重试）
     */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket()
            // 允许快速重启（stop 后立即用同一端口重新绑定）
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), BACKLOG)
            socket.soTimeout = ACCEPT_TIMEOUT_MILLIS

            val pool = Executors.newFixedThreadPool(
                THREAD_POOL_SIZE,
                ThreadFactory { runnable ->
                    Thread(runnable, "wearmail-config-web-worker").apply { isDaemon = true }
                },
            )

            serverSocket = socket
            executor = pool
            actualPort = socket.localPort
            running.set(true)
            acceptThread = thread(name = "wearmail-config-web-accept", isDaemon = true) {
                acceptLoop(socket, pool)
            }
            logInfo("配置服务已启动：端口=$actualPort")
            true
        } catch (t: Throwable) {
            // 端口被占用（BindException）是最常见的失败原因
            logWarn("配置服务启动失败：端口=$port", t)
            releaseResources()
            actualPort = -1
            false
        }
    }

    /** 停止监听并释放线程池；幂等，可重复调用 */
    fun stop() {
        val wasRunning = running.getAndSet(false)
        val thread = acceptThread
        acceptThread = null

        releaseResources()

        // 等待 accept 线程退出（有超时，不会挂死；也不会自己 join 自己）
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_TIMEOUT_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                logWarn("等待 accept 线程退出失败", t)
            }
        }

        if (wasRunning) {
            logInfo("配置服务已停止：端口=$actualPort")
        } else {
            logDebug("配置服务已停止（重复调用，已忽略）")
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** accept 循环：单个请求出错绝不能让它退出 */
    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (closed: SocketException) {
                // stop() 关闭套接字时会走到这里，属于正常退出路径
                break
            } catch (timeout: IOException) {
                // soTimeout 到期或临时 IO 错误：继续循环，顺便检查停止标志
                continue
            } catch (t: Throwable) {
                if (!running.get()) break
                logWarn("accept 异常，继续监听", t)
                continue
            }

            try {
                pool.execute { handleClient(client) }
            } catch (t: Throwable) {
                // 线程池已关闭（stop 竞态）或资源耗尽：直接关闭这条连接
                logWarn("提交请求处理任务失败，关闭连接", t)
                closeQuietly(client)
            }
        }
        logDebug("accept 循环已退出")
    }

    /** 处理一条连接：解析请求 → 交给 handler → 写回响应，任何异常都只影响当前连接 */
    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = SOCKET_READ_TIMEOUT_MILLIS
            val input = BufferedInputStream(client.getInputStream())

            val headerBytes = readHeaderBlock(input)
            if (headerBytes == null) {
                // 连上后没发数据（端口探测/健康检查），直接关闭即可
                return
            }

            val request = parseRequest(headerBytes, input)
            val response = if (request == null) {
                HttpResponse.text("请求格式错误", 400)
            } else {
                try {
                    handler(request)
                } catch (t: Throwable) {
                    logWarn("处理请求失败：${request.method} ${request.path}", t)
                    HttpResponse.text("服务器内部错误", 500)
                }
            }

            writeResponse(BufferedOutputStream(client.getOutputStream()), response)
        } catch (t: Throwable) {
            logWarn("处理连接失败", t)
        } finally {
            closeQuietly(client)
        }
    }

    /**
     * 读取请求头（直到空行）。
     *
     * 逐字节读取并自行识别 `\r\n\r\n` / `\n\n`，而不是用 readLine，
     * 这样可以保证后续按 Content-Length 读请求体时字节位置精确
     * （表单里的中文按 UTF-8 编码后字节数大于字符数，必须按字节读）。
     */
    private fun readHeaderBlock(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(INITIAL_HEADER_BUFFER)
        var newlineRun = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                // 连接被对端关闭：没有任何数据则视为空请求
                return if (buffer.size() == 0) null else String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
            }
            buffer.write(byte)
            when (byte) {
                '\n'.code -> {
                    newlineRun++
                    if (newlineRun >= 2) break
                }

                '\r'.code -> {
                    // \r 不计入，等随后的 \n 计数
                }

                else -> newlineRun = 0
            }
            if (buffer.size() > MAX_HEADER_BYTES) {
                logWarn("请求头超长，已截断")
                break
            }
        }
        return String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    /** 解析请求行、请求头、请求体 */
    private fun parseRequest(headerBytes: String, input: InputStream): HttpRequest? {
        val lines = headerBytes.split("\r\n", "\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].trim().uppercase()
        val target = parts[1].trim()

        val headers = LinkedHashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name.isNotEmpty()) headers[name] = value
        }

        val rawPath = target.substringBefore('?')
        val path = decodeComponent(rawPath)
        val urlQuery: Map<String, String> = if (target.contains('?')) {
            parseFormEncoded(target.substringAfter('?'))
        } else {
            emptyMap<String, String>()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readBody(input, contentLength)

        val contentType = headers["content-type"].orEmpty().lowercase()
        val query: Map<String, String> = if (method == "POST" && contentType.contains(FORM_URLENCODED)) {
            // 表单字段并入 query（URL 上的参数优先，例如 URL 里的 t 覆盖表单里的同名隐藏域）
            val merged = LinkedHashMap<String, String>()
            merged.putAll(parseFormEncoded(body))
            merged.putAll(urlQuery)
            merged
        } else {
            urlQuery
        }

        return HttpRequest(
            method = method,
            path = path,
            query = query,
            headers = headers,
            body = body,
        )
    }

    /** 按 Content-Length 精确读取请求体（上限 [MAX_BODY_BYTES]，防止恶意超大请求） */
    private fun readBody(input: InputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val length = minOf(contentLength, MAX_BODY_BYTES)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = try {
                input.read(buffer, read, length - read)
            } catch (t: Throwable) {
                logWarn("读取请求体失败", t)
                break
            }
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read, StandardCharsets.UTF_8)
    }

    /** 写回响应：HTTP/1.1 + Content-Type + Content-Length + Connection: close */
    private fun writeResponse(out: OutputStream, response: HttpResponse) {
        val body = response.body
        val header = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ')
            append(reasonPhrase(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            // 配置页包含账户信息，禁止任何中间缓存
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    /** 关闭监听套接字与线程池（供 start 失败与 stop 共用） */
    private fun releaseResources() {
        running.set(false)

        val socket = serverSocket
        serverSocket = null
        if (socket != null) {
            try {
                socket.close()
            } catch (t: Throwable) {
                logWarn("关闭监听套接字失败", t)
            }
        }

        val pool = executor
        executor = null
        if (pool != null) {
            try {
                pool.shutdownNow()
            } catch (t: Throwable) {
                logWarn("关闭请求线程池失败", t)
            }
        }
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) return
        try {
            socket.close()
        } catch (t: Throwable) {
            // 关闭失败无补救手段，忽略
        }
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    // 说明：本类被设计成可在纯 JVM 单元测试中运行，因此日志调用一律做兜底，
    // 避免在没有 android.util.Log 的运行环境里因为记日志而抛异常。
    private fun logInfo(message: String) {
        try {
            Logs.i(TAG, message)
        } catch (t: Throwable) {
            // 无日志实现时忽略
        }
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        try {
            Logs.w(TAG, message, throwable)
        } catch (t: Throwable) {
            // 无日志实现时忽略
        }
    }

    private fun logDebug(message: String) {
        try {
            Logs.d(TAG, message)
        } catch (t: Throwable) {
            // 无日志实现时忽略
        }
    }

    companion object {
        private const val TAG = "ConfigWebServer"

        /** 表单编码的 Content-Type 标识 */
        const val FORM_URLENCODED: String = "application/x-www-form-urlencoded"

        /** accept 超时：定期醒来检查停止标志 */
        private const val ACCEPT_TIMEOUT_MILLIS = 1_000

        /** 单条连接读写超时：手机端提交后不应长期占用 worker 线程 */
        private const val SOCKET_READ_TIMEOUT_MILLIS = 10_000

        private const val THREAD_POOL_SIZE = 2
        private const val BACKLOG = 8
        private const val STOP_JOIN_TIMEOUT_MILLIS = 500L

        private const val INITIAL_HEADER_BUFFER = 512
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024

        /**
         * 解析 `application/x-www-form-urlencoded` 文本（查询串或表单请求体）。
         *
         * 键值都用 [URLDecoder] 按 UTF-8 解码（`+` 视为空格，`%XX` 视为字节），
         * 非法百分号编码退化为原样返回，避免一个坏字段导致整个请求 400。
         */
        fun parseFormEncoded(text: String): Map<String, String> {
            if (text.isBlank()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in text.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
                val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
                val key = decodeComponent(rawKey)
                if (key.isEmpty()) continue
                result[key] = decodeComponent(rawValue)
            }
            return result
        }

        /** URL 解码；非法编码时返回原文 */
        private fun decodeComponent(raw: String): String = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (t: Throwable) {
            raw
        }
    }
}
