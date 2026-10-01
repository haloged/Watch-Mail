package com.wm.wearmail.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * [ConfigWebServer] 的纯 JVM 单元测试。
 *
 * 与其它测试不同，这里**真实启动监听套接字并用 HttpURLConnection 发请求**，
 * 因为 HTTP 请求解析/响应拼装是手写代码，只有端到端验证才能覆盖
 * 请求行、请求头、Content-Length、表单解码这些容易出错的地方。
 *
 * 端口传 0 由系统分配，避免与开发机上的服务冲突。
 * 若 CI/沙箱禁止监听端口（`start()` 返回 false），相关测试会明确报出该环境限制。
 */
class ConfigWebServerTest {

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    @Test
    fun `GET 请求可正确解析方法 路径 查询参数与请求头`() {
        val captured = AtomicReference<ConfigWebServer.HttpRequest>()
        withServer({ request ->
            captured.set(request)
            ConfigWebServer.HttpResponse.html("<p>ok</p>")
        }) { server ->
            val (status, body) = get(server.boundPort, "/pair?t=abc123&msg=%E4%BD%A0%E5%A5%BD%20%E4%B8%96%E7%95%8C&plus=a+b")

            assertEquals(200, status)
            assertEquals("<p>ok</p>", body)

            val request = captured.get()
            assertNotNull("handler 未被调用", request)
            assertEquals("GET", request.method)
            assertEquals("/pair", request.path)
            // %XX 与 + 都要按表单规则解码
            assertEquals("abc123", request.query["t"])
            assertEquals("你好 世界", request.query["msg"])
            assertEquals("a b", request.query["plus"])
            assertEquals("", request.body)
            assertTrue("应解析出请求头", request.headers.containsKey("host"))
        }
    }

    @Test
    fun `不带查询串的路径解析为空查询表`() {
        val captured = AtomicReference<ConfigWebServer.HttpRequest>()
        withServer({ request ->
            captured.set(request)
            ConfigWebServer.HttpResponse.text("ping")
        }) { server ->
            val (status, body) = get(server.boundPort, "/health")

            assertEquals(200, status)
            assertEquals("ping", body)
            val request = captured.get()
            assertEquals("/health", request.path)
            assertTrue(request.query.isEmpty())
        }
    }

    // ------------------------------------------------------------------
    // POST
    // ------------------------------------------------------------------

    @Test
    fun `POST 表单正文按 UTF-8 解码并合并进查询表`() {
        val captured = AtomicReference<ConfigWebServer.HttpRequest>()
        withServer({ request ->
            captured.set(request)
            ConfigWebServer.HttpResponse.html("已收到")
        }) { server ->
            val form = "t=token-1&email=user%40qq.com&alias=%E5%B7%A5%E4%BD%9C&password=p%26w%3D1&imapPort=993"
            val (status, body) = post(server.boundPort, "/pair", form)

            assertEquals(200, status)
            assertEquals("已收到", body)

            val request = captured.get()
            assertNotNull("handler 未被调用", request)
            assertEquals("POST", request.method)
            assertEquals("/pair", request.path)

            // body 保留原始表单文本，供需要精确解析的调用方使用
            assertEquals(form, request.body)

            // 表单字段解析结果
            assertEquals("token-1", request.query["t"])
            assertEquals("user@qq.com", request.query["email"])
            assertEquals("工作", request.query["alias"])
            assertEquals("p&w=1", request.query["password"])
            assertEquals("993", request.query["imapPort"])
        }
    }

    @Test
    fun `URL 查询串优先于同名的表单字段`() {
        val captured = AtomicReference<ConfigWebServer.HttpRequest>()
        withServer({ request ->
            captured.set(request)
            ConfigWebServer.HttpResponse.text("ok")
        }) { server ->
            post(server.boundPort, "/pair?t=from-url", "t=from-body&email=a%40b.com")

            val request = captured.get()
            assertEquals("from-url", request.query["t"])
            assertEquals("a@b.com", request.query["email"])
        }
    }

    @Test
    fun `parseFormEncoded 可直接解析表单文本且忽略空字段`() {
        val parsed = ConfigWebServer.parseFormEncoded("a=1&b=%E4%B8%AD%E6%96%87&&c=")
        assertEquals("1", parsed["a"])
        assertEquals("中文", parsed["b"])
        assertEquals("", parsed["c"])
        assertEquals(3, parsed.size)

        assertTrue(ConfigWebServer.parseFormEncoded("").isEmpty())
    }

    // ------------------------------------------------------------------
    // 状态码分支
    // ------------------------------------------------------------------

    @Test
    fun `未知路径返回 404 且带响应体`() {
        withServer({ ConfigWebServer.HttpResponse.text("未找到该页面", 404) }) { server ->
            val (status, body) = get(server.boundPort, "/not-exist")
            assertEquals(404, status)
            assertEquals("未找到该页面", body)
        }
    }

    @Test
    fun `handler 抛异常时返回 500 且服务继续可用`() {
        withServer({ request ->
            if (request.path == "/boom") error("模拟处理失败")
            ConfigWebServer.HttpResponse.text("still alive")
        }) { server ->
            val (boomStatus, _) = get(server.boundPort, "/boom")
            assertEquals(500, boomStatus)

            // 单次请求失败不能让 accept 循环退出
            val (okStatus, okBody) = get(server.boundPort, "/ok")
            assertEquals(200, okStatus)
            assertEquals("still alive", okBody)
            assertTrue(server.isRunning)
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Test
    fun `stop 幂等且停止后端口可再次绑定`() {
        val server = ConfigWebServer(0) { ConfigWebServer.HttpResponse.text("hi") }
        try {
            assertTrue("本地 HTTP 服务启动失败（可能环境禁止监听端口）", server.start())
            assertTrue(server.isRunning)
            assertTrue(server.boundPort > 0)

            assertEquals(200, get(server.boundPort, "/").first)

            server.stop()
            assertFalse(server.isRunning)

            // 重复 stop 不应抛异常
            server.stop()
            assertFalse(server.isRunning)

            // 同一端口应可立即重新绑定（reuseAddress）
            val again = ConfigWebServer(server.boundPort) { ConfigWebServer.HttpResponse.text("hi again") }
            try {
                assertTrue("停止后端口应可再次绑定", again.start())
                assertEquals(200, get(again.boundPort, "/").first)
            } finally {
                again.stop()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `端口冲突时 start 返回布尔值而不抛异常`() {
        val first = ConfigWebServer(0) { ConfigWebServer.HttpResponse.text("first") }
        try {
            assertTrue(first.start())
            val occupied = ConfigWebServer(first.boundPort) { ConfigWebServer.HttpResponse.text("second") }
            try {
                // 多数平台会因端口占用而绑定失败（返回 false）；
                // 少数平台允许 SO_REUSEADDR 共享端口，此时也必须能正常服务与关闭。
                // 两种行为都不允许抛异常，这正是本用例要守住的不变量。
                val bound = occupied.start()
                if (bound) {
                    assertEquals(200, get(occupied.boundPort, "/").first)
                } else {
                    assertFalse(occupied.isRunning)
                }
            } finally {
                occupied.stop()
            }
            // 第一个服务不受影响
            assertEquals(200, get(first.boundPort, "/").first)
        } finally {
            first.stop()
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 启动服务并保证测试结束后关闭 */
    private fun withServer(
        handler: (ConfigWebServer.HttpRequest) -> ConfigWebServer.HttpResponse,
        block: (ConfigWebServer) -> Unit,
    ) {
        val server = ConfigWebServer(0, handler)
        assertTrue("本地 HTTP 服务启动失败（可能环境禁止监听端口）", server.start())
        try {
            block(server)
        } finally {
            server.stop()
        }
    }

    /** 发一个 GET 请求，返回「状态码 to 响应体」 */
    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = openConnection(port, path, "GET")
        return try {
            val status = connection.responseCode
            status to readBody(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    /** 发一个表单 POST 请求，返回「状态码 to 响应体」 */
    private fun post(port: Int, path: String, form: String): Pair<Int, String> {
        val connection = openConnection(port, path, "POST")
        return try {
            val payload = form.toByteArray(StandardCharsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "${ConfigWebServer.FORM_URLENCODED}; charset=UTF-8",
            )
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            status to readBody(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(port: Int, path: String, method: String): HttpURLConnection {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return connection
    }

    /** 4xx/5xx 的响应体在 errorStream 上 */
    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private companion object {
        /** 连接/读取超时：测试必须快速失败，不能挂住 CI */
        const val TIMEOUT_MILLIS = 3_000
    }
}
