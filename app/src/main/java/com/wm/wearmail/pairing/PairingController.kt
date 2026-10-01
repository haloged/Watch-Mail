package com.wm.wearmail.pairing

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.ProviderPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.UUID

/**
 * 局域网扫码配对实现（[PairingService] 的唯一实现）。
 *
 * 交互流程：
 * 1. [start] 取本机 Wi-Fi 的 IPv4 地址，在 8080（占用则 8081/8082）起一个极简 HTTP 服务；
 * 2. 把 `http://<ip>:<port>/pair?t=<一次性 token>` 渲染成二维码显示在表盘；
 * 3. 手机扫码打开页面填写账户信息，POST 回手表；
 * 4. 手表端校验收到的 token（防止同网段其它设备误提交），加密保存账户。
 *
 * 安全红线（务必保持）：
 * - 日志只记录端口、账户 id 等非敏感信息，**绝不记录密码、token、表单内容**；
 * - token 只出现在 URL 与页面隐藏域中，不进日志；
 * - HTTP 仅用于局域网内的一次性配置，会话随 [stop] 立即结束。
 */
class PairingController(private val container: AppContainer) : PairingService {

    private val _state = MutableStateFlow(PairingState())

    override val state: StateFlow<PairingState> = _state.asStateFlow()

    @Volatile
    private var server: ConfigWebServer? = null

    override fun start(): PairingState {
        return try {
            val lanIp = resolveLanIp()
            if (lanIp == null) {
                Logs.w(TAG, "未取得局域网 IP，配对服务未启动")
                _state.update {
                    it.copy(
                        running = false,
                        lanIp = null,
                        qrBitmap = null,
                        url = null,
                        error = "未连接 Wi-Fi，无法扫码配对",
                    )
                }
                return _state.value
            }

            // 重新开始时先释放上一次的端口与二维码
            stop()

            // 一次性会话 token：只写入二维码与页面隐藏域
            val token = UUID.randomUUID().toString().replace("-", "")

            var bound: ConfigWebServer? = null
            var lastError: String? = null
            for (candidate in candidatePorts()) {
                val candidateServer = ConfigWebServer(candidate) { request ->
                    handleRequest(request, token, lanIp, candidate)
                }
                if (candidateServer.start()) {
                    bound = candidateServer
                    break
                }
                lastError = "端口 $candidate 已被占用"
                candidateServer.stop()
            }

            val startedServer = bound
            if (startedServer == null) {
                Logs.e(TAG, "配对服务启动失败：${lastError ?: "无可用端口"}")
                _state.update {
                    it.copy(
                        running = false,
                        lanIp = lanIp,
                        qrBitmap = null,
                        url = null,
                        error = lastError ?: "无法启动配对服务，请稍后重试",
                    )
                }
                return _state.value
            }

            server = startedServer
            val port = startedServer.boundPort
            val url = "http://$lanIp:$port/pair?t=$token"
            val qrBitmap = QrCodeRenderer.render(url, QR_SIZE_PX)
            if (qrBitmap == null) {
                // 二维码生成失败仍可用浏览器手工访问，不当作致命错误
                Logs.w(TAG, "二维码生成失败，请手工访问配对地址")
            }

            // 注意：这里只记录地址主体，token 绝不进日志
            Logs.i(TAG, "配对服务已启动：http://$lanIp:$port/pair")
            _state.update {
                it.copy(
                    running = true,
                    url = url,
                    qrBitmap = qrBitmap,
                    lanIp = lanIp,
                    port = port,
                    error = null,
                )
            }
            _state.value
        } catch (t: Throwable) {
            Logs.e(TAG, "启动配对服务异常", t)
            runCatching { server?.stop() }
            server = null
            _state.update {
                it.copy(
                    running = false,
                    qrBitmap = null,
                    error = "启动配对服务失败：${t.javaClass.simpleName}",
                )
            }
            _state.value
        }
    }

    override fun stop() {
        val current = server
        server = null
        try {
            current?.stop()
        } catch (t: Throwable) {
            Logs.w(TAG, "关闭配对服务失败", t)
        }
        _state.update { it.copy(running = false, qrBitmap = null) }
    }

    override fun renderQr(content: String, sizePx: Int): Bitmap? =
        QrCodeRenderer.render(content, sizePx)

    // ------------------------------------------------------------------
    // 请求处理
    // ------------------------------------------------------------------

    /** 处理一次 `/pair` 请求；token 校验失败一律 403 */
    private fun handleRequest(
        request: ConfigWebServer.HttpRequest,
        expectedToken: String,
        host: String,
        port: Int,
    ): ConfigWebServer.HttpResponse {
        if (request.path != PAIR_PATH) {
            return ConfigWebServer.HttpResponse.text("未找到该页面", 404)
        }

        // POST 的表单字段会由 ConfigWebServer 合并进 query，这里两处都查一遍更稳
        val form = ConfigWebServer.parseFormEncoded(request.body)
        val token = request.query[TOKEN_FIELD] ?: form[TOKEN_FIELD]
        if (token != expectedToken) {
            Logs.w(TAG, "配对请求 token 校验失败，已拒绝")
            return ConfigWebServer.HttpResponse.text("配对链接已失效，请在手表上重新生成二维码", 403)
        }

        return when (request.method) {
            "GET" -> ConfigWebServer.HttpResponse.html(
                PairingPage.render(expectedToken, ProviderPresets.all, host, port),
            )

            // 表单提交需要写数据库（挂起函数），而 handler 是同步回调：
            // 这里运行在 ConfigWebServer 的工作线程上，用 runBlocking 阻塞是安全的。
            "POST" -> runBlocking { handleSubmit(form, expectedToken) }

            else -> ConfigWebServer.HttpResponse.text("不支持的请求方法", 405)
        }
    }

    /**
     * 校验表单并保存账户（返回中文结果页）。
     *
     * @param token 本次会话 token，仅用于生成结果页里的「返回修改」链接 ——
     *   链接必须重新带上 token，否则点回去会被 403 拦掉。
     */
    private suspend fun handleSubmit(
        form: Map<String, String>,
        token: String,
    ): ConfigWebServer.HttpResponse {
        val email = form["email"].orEmpty().trim()
        if (!EMAIL_REGEX.matches(email)) {
            return errorPage("邮箱地址格式不正确（收到：「${email.take(EMAIL_ECHO_CHARS)}」），请检查后重试。", token)
        }

        val password = form["password"].orEmpty()
        if (password.isEmpty()) {
            return errorPage("请填写密码或授权码。", token)
        }

        val imapHost = form["imapHost"].orEmpty().trim()
        val smtpHost = form["smtpHost"].orEmpty().trim()

        // 服务器地址兜底：表单填全就尊重表单，否则按「显式选择的服务商 → 邮箱域名」识别。
        // 网页里的自动填充依赖内联 JS；域名不在预设表里（企业邮箱/自建/iCloud/sina…）时
        // 主机必然为空 —— 早期版本把空值直接当校验失败，用户只会看到一句「提交失败」。
        val resolved = PairingFormDefaults.resolve(
            email = email,
            formImapHost = imapHost,
            formSmtpHost = smtpHost,
            formPresetId = form["presetId"],
        ) ?: return errorPage(
            "请填写 IMAP / SMTP 服务器地址：无法根据邮箱域名自动识别，请向邮箱服务商查询后填写。",
            token,
        )

        if (resolved.usedFallback) {
            // 只记录服务商 id 与主机（非敏感信息），绝不记录凭据
            Logs.i(
                TAG,
                "配对表单未填写服务器地址，已按预设回填：preset=${resolved.presetId}, imap=${resolved.imapHost}",
            )
        }

        val imapPort = parsePort(form["imapPort"])
            ?: return errorPage(
                "IMAP 端口必须是 1-65535 之间的数字（收到：「${form["imapPort"].orEmpty().take(PORT_ECHO_CHARS)}」）。",
                token,
            )
        val smtpPort = parsePort(form["smtpPort"])
            ?: return errorPage(
                "SMTP 端口必须是 1-65535 之间的数字（收到：「${form["smtpPort"].orEmpty().take(PORT_ECHO_CHARS)}」）。",
                token,
            )

        val authType = parseAuthType(form["authType"])
        val imapSecurity = parseSecurity(form["imapSecurity"])
        val smtpSecurity = parseSecurity(form["smtpSecurity"])
        val alias = form["alias"].orEmpty().trim().take(ALIAS_MAX_CHARS)

        return try {
            // 同一邮箱再次扫码 = 重新配置：覆盖原账户，而不是撞 UNIQUE 约束后报「提交失败」
            val existing = findExistingAccount(email)
            val account = Account(
                id = existing?.id ?: 0L,
                email = email,
                alias = alias,
                authType = authType,
                imapHost = resolved.imapHost,
                imapPort = imapPort,
                imapSecurity = imapSecurity,
                smtpHost = resolved.smtpHost,
                smtpPort = smtpPort,
                smtpSecurity = smtpSecurity,
                // 重新配置时保留原有的标识色、通知开关与同步时间
                colorIndex = existing?.colorIndex ?: Account.colorIndexFor(email),
                notificationsEnabled = existing?.notificationsEnabled ?: true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastSyncAt = existing?.lastSyncAt ?: 0L,
            )

            // 凭据只经内存传给仓储（仓储内部用 CryptoManager 加密落盘）。
            // 说明：手机网页只能提供「粘贴已有令牌」这种最简形式 —— 令牌写进 password 字段，
            // 由 AccountSecrets.authSecret 统一取出，协议层因此不需要分支。
            // 完整的设备码授权（自动获取 + 自动续期）在手表端
            // 「添加账户 → 登录方式选 OAuth2 令牌」里完成，实现见 mail/oauth。
            val secrets = AccountSecrets(password = password)
            val savedId = if (existing != null) {
                if (container.accounts.update(account, secrets)) existing.id else 0L
            } else {
                container.accounts.add(account, secrets)
            }
            if (savedId <= 0L) {
                return errorPage("手表端保存失败，请重试，或改用手表上的「添加账户」。", token)
            }

            // 刷新内存快照，让手表界面立刻看到新账户
            runCatching { container.accounts.load() }
                .onFailure { Logs.w(TAG, "刷新账户列表失败", it) }

            val updated = existing != null
            Logs.i(TAG, if (updated) "配对成功：已更新账户 id=$savedId" else "配对成功：已保存账户 id=$savedId")
            _state.update {
                it.copy(
                    submittedCount = it.submittedCount + 1,
                    lastMessage = (if (updated) "已更新账户：" else "已保存账户：") + account.displayLabel,
                    error = null,
                )
            }
            successPage(account.displayLabel, updated, token)
        } catch (t: Throwable) {
            // 只记录异常类型，绝不打印表单内容
            Logs.e(TAG, "保存配对账户失败：${t.javaClass.simpleName}", t)
            errorPage("手表端保存失败（${t.javaClass.simpleName}），请重试，或改用手表上的「添加账户」。", token)
        }
    }

    /**
     * 查找同邮箱的已有账户。
     *
     * 优先用内存快照（通常已预热）；快照为空时再触发一次加载 ——
     * 冷启动后立刻扫码的情形下快照可能还没加载完。
     */
    private suspend fun findExistingAccount(email: String): Account? {
        val cached = container.accounts.accounts.value
            .firstOrNull { it.email.equals(email, ignoreCase = true) }
        if (cached != null) return cached

        val loaded = runCatching { container.accounts.load() }.getOrNull() ?: return null
        return loaded.firstOrNull { it.email.equals(email, ignoreCase = true) }
    }

    // ------------------------------------------------------------------
    // 网络地址解析
    // ------------------------------------------------------------------

    /**
     * 取本机局域网 IPv4 地址。
     *
     * 优先遍历网卡：`isUp && !isLoopback` 且持有 IPv4 地址的 `wlan*` 网卡；
     * 没有 wlan 网卡时退化为第一个非回环 IPv4 地址；
     * 都拿不到再退回 WifiManager（需要 ACCESS_WIFI_STATE，已在 Manifest 声明）。
     */
    private fun resolveLanIp(): String? {
        try {
            var fallback: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (networkInterface in interfaces) {
                    val usable = try {
                        networkInterface.isUp && !networkInterface.isLoopback
                    } catch (t: Throwable) {
                        // 网卡状态查询失败（SocketException）时跳过该网卡
                        false
                    }
                    if (!usable) continue

                    for (address in networkInterface.inetAddresses) {
                        if (address.isLoopbackAddress || address !is Inet4Address) continue
                        val ip = address.hostAddress?.substringBefore('%')?.trim().orEmpty()
                        if (ip.isEmpty()) continue
                        if (networkInterface.name.startsWith(WIFI_INTERFACE_PREFIX)) {
                            return ip
                        }
                        if (fallback == null) fallback = ip
                    }
                }
            }
            if (fallback != null) return fallback
        } catch (t: Throwable) {
            Logs.w(TAG, "枚举网络接口失败，尝试 WifiManager", t)
        }
        return wifiManagerIp()
    }

    /** 兜底方案：从 WifiManager 读连接信息（ipAddress 为小端序 int） */
    @Suppress("DEPRECATION")
    private fun wifiManagerIp(): String? {
        return try {
            val wifiManager = container.app.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ip == 0) null else formatIpv4(ip)
        } catch (t: Throwable) {
            Logs.w(TAG, "读取 Wi-Fi 地址失败", t)
            null
        }
    }

    /** WifiManager 返回的 IPv4 为小端序整数，需按低字节在前还原 */
    private fun formatIpv4(value: Int): String =
        "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"

    // ------------------------------------------------------------------
    // 表单解析与页面片段
    // ------------------------------------------------------------------

    private fun parsePort(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return if (value in MIN_PORT..MAX_PORT) value else null
    }

    private fun parseAuthType(raw: String?): AuthType =
        AuthType.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
            ?: AuthType.APP_PASSWORD

    private fun parseSecurity(raw: String?): MailSecurity =
        MailSecurity.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
            ?: MailSecurity.SSL_TLS

    private fun successPage(label: String, updated: Boolean, token: String): ConfigWebServer.HttpResponse =
        ConfigWebServer.HttpResponse.html(
            resultPage(
                title = if (updated) "已更新账户" else "已保存账户",
                message = "「$label」的配置已加密保存到手表，可关闭本页回到手表继续。",
                success = true,
                token = token,
            ),
        )

    /**
     * 失败结果页。
     *
     * 标题**直接使用具体原因**：手机上用户通常只看大标题，
     * 早期版本标题固定为「提交失败」，导致「主机没填」「端口写成全角数字」
     * 这类问题无法自诊断，反馈里只能看到"提交失败"。
     */
    private fun errorPage(reason: String, token: String): ConfigWebServer.HttpResponse =
        ConfigWebServer.HttpResponse.html(
            resultPage(
                title = reason,
                message = "请返回上一页修改后重试。",
                success = false,
                token = token,
            ),
            400,
        )

    /** 极简结果页：中文、深色、可带 token 返回重填 */
    private fun resultPage(
        title: String,
        message: String,
        success: Boolean,
        token: String,
    ): String {
        val accent = if (success) "#81c995" else "#f28b82"
        val safeTitle = escapeHtml(title)
        val safeMessage = escapeHtml(message)
        // 返回链接必须带回 token，否则会命中 403「配对链接已失效」
        val retryUrl = "/pair?t=" + URLEncoder.encode(token, "UTF-8")
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="color-scheme" content="dark">
<title>$safeTitle - WearMail</title>
<style>
body {
  margin: 0; padding: 40px 20px;
  background: #101114; color: #e8eaed;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 17px; line-height: 1.6; text-align: center;
}
h1 { font-size: 22px; color: $accent; margin: 0 0 12px; }
p { margin: 0 0 24px; }
a { color: #8ab4f8; }
</style>
</head>
<body>
<h1>$safeTitle</h1>
<p>$safeMessage</p>
<p><a href="$retryUrl">返回修改</a></p>
</body>
</html>
""".trimIndent()
    }

    private fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun candidatePorts(): List<Int> =
        (0 until MAX_PORT_ATTEMPTS).map { offset -> PairingState.DEFAULT_PORT + offset }

    companion object {
        private const val TAG = "Pairing"

        private const val PAIR_PATH = "/pair"
        private const val TOKEN_FIELD = "t"

        /** 二维码像素尺寸（466x466 表盘上留出边距） */
        private const val QR_SIZE_PX = 300

        /** 端口尝试次数：8080、8081、8082 */
        private const val MAX_PORT_ATTEMPTS = 3

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        private const val ALIAS_MAX_CHARS = 32

        /** 端口校验失败时回显用户输入的字符数上限（便于发现全角数字等问题） */
        private const val PORT_ECHO_CHARS = 8

        /** 邮箱校验失败时回显的字符数上限（用户输入不含敏感信息） */
        private const val EMAIL_ECHO_CHARS = 40

        private const val WIFI_INTERFACE_PREFIX = "wlan"

        /** 邮箱格式校验：够用即可（不追求 RFC 5322 完备性） */
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    }
}
