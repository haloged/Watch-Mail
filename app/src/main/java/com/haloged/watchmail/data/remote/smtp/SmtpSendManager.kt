package com.haloged.watchmail.data.remote.smtp

import android.util.Log
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.local.entity.EncryptionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * SMTP发送结果
 */
sealed class SmtpSendResult {
    object Success : SmtpSendResult()
    data class Error(val message: String, val exception: Throwable? = null) : SmtpSendResult()
}

/**
 * SMTP邮件发送管理器
 * 负责通过SMTP协议发送邮件
 *
 * ⚠ 所有 catch 一律吞 Throwable，防止 JavaMail 在 Android 上抛出的
 *   NoClassDefFoundError / OOM 等 Error 冒泡导致闪退
 */
class SmtpSendManager {

    companion object {
        private const val TAG = "SmtpSendManager"
        private const val CONNECTION_TIMEOUT = 10000 // 10秒连接超时
        private const val WRITE_TIMEOUT = 30000 // 30秒写入超时
        private const val MAX_BODY_LENGTH = 500 // 正文最大长度
    }

    /**
     * 测试SMTP连接
     * @param account 账户配置
     * @return 连接是否成功
     */
    suspend fun testConnection(account: AccountEntity): Boolean = withContext(Dispatchers.IO) {
        var transport: Transport? = null
        try {
            val session = createSession(account)
            transport = session.getTransport("smtp")
            transport.connect(
                account.smtpHost,
                account.smtpPort,
                account.email,
                getDecryptedPassword(account)
            )
            Log.d(TAG, "SMTP连接测试成功: ${account.email}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "SMTP连接测试失败: ${account.email}", e)
            false
        } finally {
            closeTransport(transport)
        }
    }

    /**
     * 发送邮件
     * @param account 发件账户
     * @param toAddress 收件人地址
     * @param subject 邮件主题
     * @param body 邮件正文
     * @return 发送结果
     */
    suspend fun sendEmail(
        account: AccountEntity,
        toAddress: String,
        subject: String,
        body: String
    ): SmtpSendResult = withContext(Dispatchers.IO) {
        var transport: Transport? = null

        try {
            Log.d(TAG, "开始发送邮件: ${account.email} -> $toAddress")

            // 验证输入
            if (toAddress.isBlank()) {
                return@withContext SmtpSendResult.Error("收件人地址不能为空")
            }

            if (body.length > MAX_BODY_LENGTH) {
                return@withContext SmtpSendResult.Error("正文超过${MAX_BODY_LENGTH}字限制")
            }

            val session = createSession(account)
            transport = session.getTransport("smtp")

            // 连接SMTP服务器
            transport.connect(
                account.smtpHost,
                account.smtpPort,
                account.email,
                getDecryptedPassword(account)
            )

            // 创建邮件消息
            val message = createMessage(session, account, toAddress, subject, body)

            // 发送邮件
            transport.sendMessage(message, message.allRecipients)

            Log.d(TAG, "邮件发送成功: ${account.email} -> $toAddress")

            SmtpSendResult.Success

        } catch (e: AuthenticationFailedException) {
            Log.e(TAG, "SMTP认证失败: ${account.email}", e)
            val errorMsg = buildSmtpAuthErrorMessage(account.email)
            SmtpSendResult.Error(errorMsg, e)
        } catch (e: MessagingException) {
            Log.e(TAG, "SMTP发送失败: ${account.email}", e)
            SmtpSendResult.Error("发送失败: ${e.message}", e)
        } catch (t: Throwable) {
            // Throwable 兜底：Error 也不能冒泡
            Log.e(TAG, "SMTP未知错误: ${account.email}", t)
            SmtpSendResult.Error("发送失败: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            closeTransport(transport)
        }
    }

    /**
     * 创建邮件消息
     */
    private fun createMessage(
        session: Session,
        account: AccountEntity,
        toAddress: String,
        subject: String,
        body: String
    ): MimeMessage {
        val message = MimeMessage(session)

        // 设置发件人
        val fromName = account.alias.ifBlank { account.email }
        message.setFrom(InternetAddress(account.email, fromName, "UTF-8"))

        // 设置收件人
        message.setRecipients(Message.RecipientType.TO, toAddress)

        // 设置主题
        message.setSubject(subject, "UTF-8")

        // 设置正文（纯文本）
        message.setText(body, "UTF-8")

        // 设置发送时间
        message.sentDate = java.util.Date()

        return message
    }

    /**
     * 创建SMTP Session
     */
    private fun createSession(account: AccountEntity): Session {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", account.smtpHost)
            put("mail.smtp.port", account.smtpPort.toString())
            put("mail.smtp.connectiontimeout", CONNECTION_TIMEOUT.toString())
            put("mail.smtp.timeout", WRITE_TIMEOUT.toString())
            put("mail.smtp.auth", "true")

            when (account.smtpEncryption) {
                EncryptionType.SSL -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.trust", account.smtpHost)
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.socketFactory.port", account.smtpPort.toString())
                    put("mail.smtp.socketFactory.fallback", "false")
                }
                EncryptionType.TLS -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.trust", account.smtpHost)
                }
                EncryptionType.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                    put("mail.smtp.ssl.trust", account.smtpHost)
                }
                EncryptionType.NONE -> {
                    put("mail.smtp.ssl.enable", "false")
                }
            }
        }

        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(
                    account.email,
                    getDecryptedPassword(account)
                )
            }
        })
    }

    /**
     * 获取解密后的密码（KeyStore + AES-GCM）
     */
    private fun getDecryptedPassword(account: AccountEntity): String {
        return try {
            com.haloged.watchmail.util.EncryptionUtil.decrypt(account.encryptedPassword)
        } catch (e: Throwable) {
            Log.e(TAG, "密码解密失败", e)
            throw MessagingException("密码解密失败", e as? Exception)
        }
    }

    /**
     * 安全关闭Transport
     */
    private fun closeTransport(transport: Transport?) {
        try {
            if (transport != null && transport.isConnected) {
                transport.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "关闭Transport失败", t)
        }
    }
}

/**
 * 构建SMTP认证错误提示信息
 */
private fun buildSmtpAuthErrorMessage(email: String): String {
    val domain = email.substringAfter("@").lowercase()
    return when {
        domain.contains("gmail") ->
            "发送失败：Gmail需要应用专用密码"
        domain.contains("outlook") || domain.contains("hotmail") ->
            "发送失败：Outlook需要应用密码"
        domain.contains("qq") ->
            "发送失败：QQ邮箱需要授权码"
        domain.contains("163") || domain.contains("126") ->
            "发送失败：网易邮箱需要授权码"
        else ->
            "发送失败：请检查密码或应用专用密码"
    }
}
