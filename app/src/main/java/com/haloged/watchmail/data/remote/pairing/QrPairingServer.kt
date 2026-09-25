package com.haloged.watchmail.data.remote.pairing

import android.util.Base64
import android.util.Log
import com.haloged.watchmail.data.local.entity.EncryptionType
import com.haloged.watchmail.util.NetworkUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 手机扫码配对提交的账户配置
 *
 * 服务器字段为 null/空 时表示「交给手表自动探测」
 */
data class PairingAccountPayload(
    val email: String,
    val password: String,
    val alias: String,
    val imapHost: String? = null,
    val imapPort: Int? = null,
    val imapEncryption: EncryptionType? = null,
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
    val smtpEncryption: EncryptionType? = null
) {
    /** 是否需要手表端自动探测服务器配置 */
    val needsAutoDetect: Boolean
        get() = imapHost.isNullOrBlank() || smtpHost.isNullOrBlank()
}

/**
 * 配对服务状态
 */
sealed class PairingState {
    /** 等待手机提交 */
    object Waiting : PairingState()
    /** 已收到并成功解密配置 */
    data class Received(val payload: PairingAccountPayload) : PairingState()
    /** 出错（含解密失败、非法请求、超时等） */
    data class Error(val message: String) : PairingState()
}

/**
 * 扫码配对本地 Web 服务
 *
 * 流程：
 *  1. 手表在局域网内监听 HTTP 端口，生成二维码（内容 = http://IP:PORT/#k=配对码）
 *  2. 手机扫码后打开 H5 表单，填入邮箱配置
 *  3. 浏览器用「配对码」派生密钥，把整个表单加密后 POST /submit
 *  4. 手表用同一配对码解密，回调上层落库
 *
 * 加密方案（与 PairingWebPage 的纯 JS 实现严格一致）：
 *   master = SHA-256(utf8(pin))
 *   encKey = SHA-256(master || "enc")
 *   macKey = SHA-256(master || "mac")
 *   ks[i]  = SHA-256(encKey || iv || u32be(i))   // SHA-256-CTR 流密码
 *   ct     = pt XOR ks
 *   tag    = HMAC-SHA256(macKey, iv || ct)[0..16) // encrypt-then-MAC
 *
 * ⚠ 为什么不使用 WebCrypto/AES-GCM：
 *   手表服务跑在 `http://IP:PORT`，**不是安全上下文**，浏览器会禁用 `crypto.subtle`。
 *   因此手机端必须用纯 JS 自实现加解密。SHA-256 + HMAC 是浏览器/Android 都能原生实现的
 *   最小密码学原语，避免引入体积大、易错的纯 JS AES。
 *
 * 安全设计：
 *  - 配对码放在 URL fragment（#k=…），fragment 不会出现在 HTTP 请求行/请求头，
 *    因此局域网抓包看不到配对码，也就无法解密或伪造提交
 *  - encrypt-then-MAC：先校验 128 位 tag（常数时间比较），校验通过才解密，防篡改/伪造
 *  - 每次开屏重新生成配对码，单次会话、单次有效（收到一次即关闭）
 *  - 5 分钟超时自动关闭；连续 5 次校验失败即关闭（防暴力猜测）
 *  - 密码全程密文传输，落库前仍会再走 EncryptionUtil（KeyStore+AES-GCM）加密
 */
class QrPairingServer(
    private val port: Int = DEFAULT_PORT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "QrPairingServer"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L   // 5 分钟有效
        private const val MAX_FAILED_ATTEMPTS = 5        // 连续校验失败上限
        private const val IV_LENGTH = 16                 // IV 长度（字节）
        private const val TAG_LENGTH = 16                // MAC 截断长度（128 位）

        /** 生成 12 位配对码（去掉易混字符 0/O/1/I/L） */
        private fun generatePin(): String {
            val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
            val sb = StringBuilder(12)
            repeat(12) { sb.append(alphabet[(Math.random() * alphabet.length).toInt()]) }
            return sb.toString()
        }
    }

    /** 配对码 —— 派生加密密钥的唯一秘密，永不出现在 HTTP 报文中 */
    val pin: String = generatePin()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var timeoutThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var delivered = false
    private val failedAttempts = AtomicInteger(0)

    private val _state = MutableStateFlow<PairingState>(PairingState.Waiting)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /** 实际监听端口（端口被占用时会自动换一个） */
    var boundPort: Int = port
        private set

    /** 二维码内容：配对码放在 fragment，不会被发送到网络 */
    val qrContent: String
        get() = "http://${NetworkUtil.getLocalIpAddress() ?: "127.0.0.1"}:$boundPort/#k=$pin"

    /** 展示用基础地址（不含配对码，供手动输入） */
    val baseUrl: String
        get() = "http://${NetworkUtil.getLocalIpAddress() ?: "127.0.0.1"}:$boundPort/"

    /**
     * 启动本地 Web 服务
     * @return true 表示启动成功（可开始展示二维码）
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true

        val ip = NetworkUtil.getLocalIpAddress()
        if (ip == null) {
            _state.value = PairingState.Error("未连接到 WiFi，无法建立局域网配对通道")
            return false
        }

        return try {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            } catch (e: Exception) {
                // 默认端口被占用则改用随机可用端口
                Log.w(TAG, "端口 $port 不可用，改用随机端口", e)
                ServerSocket(0)
            }
            serverSocket = socket
            boundPort = socket.localPort
            running = true
            delivered = false
            failedAttempts.set(0)

            acceptThread = Thread({ acceptLoop() }, "qr-pairing-accept").apply {
                isDaemon = true
                start()
            }

            // 超时自动关闭，避免服务常驻耗电/暴露端口
            timeoutThread = Thread({
                try {
                    Thread.sleep(timeoutMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (running && !delivered) {
                    Log.d(TAG, "配对超时，自动关闭服务")
                    _state.value = PairingState.Error("配对超时，请重新发起")
                    stop()
                }
            }, "qr-pairing-timeout").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "配对服务已启动: $baseUrl  pin=$pin")
            true
        } catch (e: Exception) {
            Log.e(TAG, "配对服务启动失败", e)
            _state.value = PairingState.Error("启动本地服务失败: ${e.message}")
            false
        }
    }

    /**
     * 停止服务并释放端口
     */
    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ServerSocket 失败", e)
        }
        serverSocket = null
        acceptThread?.interrupt()
        timeoutThread?.interrupt()
        acceptThread = null
        timeoutThread = null
        Log.d(TAG, "配对服务已停止")
    }

    // ==================== HTTP 主循环 ====================

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            try {
                val client: Socket = server.accept()
                // 单线程串行处理即可（配对场景并发极低，省线程省内存）
                handleClient(client)
            } catch (e: SocketException) {
                // socket 被 stop() 关闭时会走到这里
                if (running) Log.w(TAG, "accept 异常", e)
                break
            } catch (e: Exception) {
                if (running) Log.w(TAG, "处理连接异常", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 8000
                val input = s.getInputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(s.getOutputStream(), 400, "text/plain; charset=utf-8", "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore("?").substringBefore("#")

                // 读请求头
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        writeResponse(
                            s.getOutputStream(), 200,
                            "text/html; charset=utf-8", PairingWebPage.HTML
                        )
                    }
                    method == "POST" && path == "/submit" -> {
                        handleSubmit(s, reader, headers)
                    }
                    method == "GET" && path == "/favicon.ico" -> {
                        writeResponse(s.getOutputStream(), 204, "text/plain", "")
                    }
                    else -> {
                        writeResponse(s.getOutputStream(), 404, "text/plain; charset=utf-8", "Not Found")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "处理 HTTP 请求失败", e)
                try {
                    writeResponse(s.getOutputStream(), 500, "application/json", """{"ok":false,"error":"server error"}""")
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 处理手机端提交：解密 → 解析 → 回调上层
     */
    private fun handleSubmit(
        socket: Socket,
        reader: BufferedReader,
        headers: Map<String, String>
    ) {
        val out = socket.getOutputStream()

        if (delivered) {
            writeResponse(out, 409, "application/json", """{"ok":false,"error":"本次配对已完成"}""")
            return
        }

        // 读 body
        val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLen <= 0 || contentLen > 32 * 1024) {
            writeResponse(out, 400, "application/json", """{"ok":false,"error":"invalid body"}""")
            return
        }
        val buf = CharArray(contentLen)
        var read = 0
        while (read < contentLen) {
            val n = reader.read(buf, read, contentLen - read)
            if (n < 0) break
            read += n
        }
        val body = String(buf, 0, read)

        try {
            val json = JSONObject(body)
            val ivB64 = json.getString("iv")
            val ctB64 = json.getString("ct")
            val tagB64 = json.getString("tag")

            val plain = decrypt(ivB64, ctB64, tagB64)
            val payload = parsePayload(plain)

            delivered = true
            Log.d(TAG, "配对成功: ${payload.email}")
            _state.value = PairingState.Received(payload)
            writeResponse(out, 200, "application/json", """{"ok":true}""")

            // 一次性会话：回完响应立刻关闭，防止端口继续暴露
            Thread { stop() }.apply { isDaemon = true; start() }

        } catch (e: Exception) {
            val attempts = failedAttempts.incrementAndGet()
            Log.w(TAG, "配对提交处理失败（第 $attempts 次）: ${e.message}")

            if (attempts >= MAX_FAILED_ATTEMPTS) {
                _state.value = PairingState.Error("多次校验失败，配对已关闭")
                writeResponse(out, 429, "application/json", """{"ok":false,"error":"too many attempts"}""")
                stop()
            } else {
                writeResponse(
                    out, 401,
                    "application/json",
                    """{"ok":false,"error":"配对码校验失败，请核对手表屏幕上的配对码"}"""
                )
            }
        }
    }

    // ==================== 加解密（与手机端纯 JS 实现严格一致） ====================

    /** SHA-256 */
    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** HMAC-SHA256 */
    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    /** 4 字节大端序编码（与 JS u32be() 一致） */
    private fun u32be(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xff).toByte(),
        ((n ushr 16) and 0xff).toByte(),
        ((n ushr 8) and 0xff).toByte(),
        (n and 0xff).toByte()
    )

    /**
     * 派生密钥
     * master = SHA-256(pin)；encKey = SHA-256(master||"enc")；macKey = SHA-256(master||"mac")
     */
    private fun deriveKeys(): Pair<ByteArray, ByteArray> {
        val master = sha256(pin.toByteArray(Charsets.UTF_8))
        val encKey = sha256(master + "enc".toByteArray(Charsets.UTF_8))
        val macKey = sha256(master + "mac".toByteArray(Charsets.UTF_8))
        return encKey to macKey
    }

    /**
     * 解密提交内容：先校验 MAC（encrypt-then-MAC），再按 SHA-256-CTR 还原明文
     *
     * @throws SecurityException MAC 校验失败（配对码错误或数据被篡改）
     */
    private fun decrypt(ivB64: String, ctB64: String, tagB64: String): String {
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val tag = Base64.decode(tagB64, Base64.NO_WRAP)

        if (iv.size != IV_LENGTH) throw IllegalArgumentException("invalid iv length")
        if (tag.size != TAG_LENGTH) throw IllegalArgumentException("invalid tag length")

        val (encKey, macKey) = deriveKeys()

        // 1) 先校验 MAC —— 常数时间比较，防时序侧信道
        val expected = hmacSha256(macKey, iv + ct).copyOf(TAG_LENGTH)
        if (!MessageDigest.isEqual(expected, tag)) {
            throw SecurityException("配对码校验失败")
        }

        // 2) SHA-256-CTR 解密：ks[i] = SHA-256(encKey || iv || u32be(i))
        val pt = ByteArray(ct.size)
        var counter = 0
        var off = 0
        while (off < ct.size) {
            val ks = sha256(encKey + iv + u32be(counter))
            val n = minOf(32, ct.size - off)
            for (j in 0 until n) {
                pt[off + j] = (ct[off + j].toInt() xor ks[j].toInt()).toByte()
            }
            off += 32
            counter++
        }
        return String(pt, Charsets.UTF_8)
    }

    /**
     * 解析解密后的账户配置 JSON
     * 服务器字段留空 → 保持 null，交由手表端自动探测
     */
    private fun parsePayload(plain: String): PairingAccountPayload {
        val o = JSONObject(plain)

        val email = o.getString("email").trim()
        val password = o.getString("password")
        val alias = o.optString("alias", "").trim()

        if (email.isBlank() || password.isBlank()) {
            throw IllegalArgumentException("邮箱或密码为空")
        }

        fun enc(key: String): EncryptionType? {
            val v = o.optString(key, "").trim()
            return if (v.isEmpty()) null else runCatching { EncryptionType.valueOf(v) }.getOrNull()
        }
        fun host(key: String): String? = o.optString(key, "").trim().takeIf { it.isNotEmpty() }
        fun portOf(key: String): Int? = o.optInt(key, 0).takeIf { it > 0 }

        return PairingAccountPayload(
            email = email,
            password = password,
            alias = alias,
            imapHost = host("imapHost"),
            imapPort = portOf("imapPort"),
            imapEncryption = enc("imapEncryption"),
            smtpHost = host("smtpHost"),
            smtpPort = portOf("smtpPort"),
            smtpEncryption = enc("smtpEncryption")
        )
    }

    // ==================== HTTP 响应 ====================

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            204 -> "204 No Content"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            409 -> "409 Conflict"
            429 -> "429 Too Many Requests"
            else -> "500 Internal Server Error"
        }
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            // H5 与服务同源，无需 CORS；加基础安全响应头
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (code != 204) out.write(bytes)
        out.flush()
    }
}
