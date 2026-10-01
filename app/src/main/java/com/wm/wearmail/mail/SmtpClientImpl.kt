package com.wm.wearmail.mail

import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * SMTP 发送客户端实现（JavaMail / android-mail 1.6.7）。
 *
 * 设计要点：
 * - 无状态：每次发送自建连接与 Session，发送完成后立即关闭；
 * - **不使用静态 `Transport.send`**：它内部自行建连/断连，无法控制超时与释放时机，
 *   在手表端弱网下容易留下悬挂连接；
 * - 正文超长只截断、不抛错：手表输入成本极高，绝不因长度问题丢失用户已写内容；
 * - 日志只记录主机、端口、收件人数量与正文长度，**不记录密码 / token / 正文**。
 */
class SmtpClientImpl : SmtpClient {

    override suspend fun testConnection(
        account: Account,
        secrets: AccountSecrets,
        timeoutMillis: Int,
    ): Result<Unit> =
        // 仅验证「能建连 + 能通过认证」，不发信
        withTransport(account, secrets, timeoutMillis) { _, _ -> }

    override suspend fun send(
        account: Account,
        secrets: AccountSecrets,
        message: OutgoingMessage,
        timeoutMillis: Int,
    ): Result<Unit> {
        val to = clean(message.to)
        val cc = clean(message.cc)
        val bcc = clean(message.bcc)

        // 收件人校验先于建连：没有收件人时连服务器都是浪费（也避免被服务商判定为异常登录）
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            Logs.w(TAG, "拒绝发送：收件人为空")
            return Result.failure(MailError.Config("收件人不能为空"))
        }

        val total = to.size + cc.size + bcc.size
        if (total > OutgoingMessage.MAX_RECIPIENTS) {
            // 上限属于 UI 层的引导，这里只告警不静默丢弃收件人
            Logs.w(TAG, "收件人数量 $total 超过建议上限 ${OutgoingMessage.MAX_RECIPIENTS}，仍继续发送")
        }

        val body = truncateBody(message.body)

        return withTransport(account, secrets, timeoutMillis) { session, transport ->
            val mime = MimeMessage(session)
            mime.setFrom(InternetAddress(account.email))
            if (to.isNotEmpty()) mime.setRecipients(Message.RecipientType.TO, parseRecipients(to))
            if (cc.isNotEmpty()) mime.setRecipients(Message.RecipientType.CC, parseRecipients(cc))
            if (bcc.isNotEmpty()) mime.setRecipients(Message.RecipientType.BCC, parseRecipients(bcc))
            mime.setSubject(message.subject, "UTF-8")
            mime.setSentDate(Date())
            mime.setText(body, "UTF-8")
            mime.saveChanges()

            // 显式传入全部收件人（含 BCC）：JavaMail 在投递时会自动去掉 Bcc 头
            transport.sendMessage(mime, mime.allRecipients)

            Logs.i(
                TAG,
                "邮件已发送：host=${account.smtpHost} port=${account.smtpPort} " +
                    "收件人=${to.size}/抄送=${cc.size}/密送=${bcc.size} 正文长度=${body.length}",
            )
        }
    }

    override fun close() {
        // 无状态实现没有连接池需要释放；保留该入口以便将来引入连接复用
        Logs.d(TAG, "SmtpClientImpl.close()：无状态实现，无需释放资源")
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /**
     * 建立 SMTP 连接、执行 [block]（参数为 Session 与已认证的 Transport）、必定关闭连接。
     * 所有异常统一包装为 [MailError]。
     */
    private suspend fun <T> withTransport(
        account: Account,
        secrets: AccountSecrets,
        timeoutMillis: Int,
        block: (Session, Transport) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val session = createSession(account, secrets, timeoutMillis)
        var transport: Transport? = null
        try {
            val newTransport = session.getTransport("smtp")
            newTransport.connect(
                account.smtpHost,
                account.smtpPort,
                account.email,
                credentialFor(account, secrets),
            )
            transport = newTransport
            Logs.d(TAG, "SMTP 已连接：host=${account.smtpHost} port=${account.smtpPort}")
            Result.success(block(session, newTransport))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logs.e(TAG, "SMTP 操作失败：host=${account.smtpHost} port=${account.smtpPort}", t)
            Result.failure(t.toMailFailure())
        } finally {
            runCatching { transport?.close() }
        }
    }

    private fun createSession(account: Account, secrets: AccountSecrets, timeoutMillis: Int): Session {
        val user = account.email
        val credential = credentialFor(account, secrets)
        // Authenticator 作为兜底凭据来源；实际生效的是 connect(host, port, user, password) 的显式参数
        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(user, credential)
        }
        return Session.getInstance(propertiesFor(account, timeoutMillis), authenticator)
    }

    /**
     * 登录凭据。
     *
     * SASL XOAUTH2 要求把 access token 放在"密码"位置（机制名见 [propertiesFor]）；
     * [AccountSecrets.authSecret] 同时兼容「OAuth2 授权得到的令牌」与
     * 「用户手动粘贴的令牌」两条路径。令牌的自动刷新由
     * [com.wm.wearmail.mail.oauth.OAuthTokenService] 负责。
     */
    private fun credentialFor(account: Account, secrets: AccountSecrets): String =
        when (account.authType) {
            AuthType.OAUTH2 -> secrets.authSecret
            else -> secrets.password
        }

    private fun propertiesFor(account: Account, timeoutMillis: Int): Properties {
        // 避免调用方传 0 被 JavaMail 解释为「永不超时」
        val timeout = timeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

        return Properties().apply {
            setProperty("mail.transport.protocol", "smtp")
            setProperty("mail.smtp.auth", "true")
            when (account.smtpSecurity) {
                MailSecurity.SSL_TLS -> {
                    // 465：建连即 TLS
                    setProperty("mail.smtp.ssl.enable", "true")
                }

                MailSecurity.STARTTLS -> {
                    // 587：先明文建连，再升级为 TLS（required 保证不会静默降级为明文）
                    setProperty("mail.smtp.starttls.enable", "true")
                    setProperty("mail.smtp.starttls.required", "true")
                }

                MailSecurity.NONE -> {
                    // 企业内网自建服务器可能完全不加密
                    setProperty("mail.smtp.starttls.enable", "false")
                    setProperty("mail.smtp.starttls.required", "false")
                    setProperty("mail.smtp.ssl.enable", "false")
                }
            }
            // 连接 10 秒 / 读 20 秒（由调用方传入的 timeoutMillis 决定，默认即 10s/20s）
            setProperty("mail.smtp.connectiontimeout", timeout.toString())
            setProperty("mail.smtp.timeout", timeout.toString())
            setProperty("mail.smtp.writetimeout", timeout.toString())
            if (account.authType == AuthType.OAUTH2) {
                setProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
            }
        }
    }

    // ------------------------------------------------------------------
    // 消息构造
    // ------------------------------------------------------------------

    /** 去掉空白项；手表端 UI 可能留下空行 */
    private fun clean(addresses: List<String>): List<String> =
        addresses.map { it.trim() }.filter { it.isNotEmpty() }

    /** 宽松解析收件人；解析失败转为 [MailError.Config]，提示用户修正地址 */
    private fun parseRecipients(addresses: List<String>): Array<InternetAddress> =
        try {
            InternetAddress.parse(addresses.joinToString(","), false)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw MailError.Config("收件人地址格式不正确：${t.message}")
        }

    /** 正文超长时截断（而不是抛错），保证用户已写内容不丢失 */
    private fun truncateBody(body: String): String {
        if (body.length <= OutgoingMessage.MAX_BODY_CHARS) return body
        Logs.w(TAG, "正文超过 ${OutgoingMessage.MAX_BODY_CHARS} 字，已截断（原长 ${body.length}）")
        return body.take(OutgoingMessage.MAX_BODY_CHARS)
    }

    private companion object {
        const val TAG = "SmtpClient"

        /** 超时下限：避免调用方传 0 被 JavaMail 当作「永不超时」 */
        const val MIN_TIMEOUT_MILLIS = 1_000
    }
}
