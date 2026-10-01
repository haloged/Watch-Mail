package com.wm.wearmail.mail

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets

/**
 * 待发送邮件。
 *
 * @param to 收件人地址列表（已解析为纯地址，不含显示名）
 * @param body 纯文本正文，手表端限制 500 字以内
 */
data class OutgoingMessage(
    val to: List<String>,
    val subject: String,
    val body: String,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
) {
    companion object {
        /** 正文长度上限（需求：建议限制 500 字以内） */
        const val MAX_BODY_CHARS: Int = 500

        /** 收件人数量上限，防止误操作群发拖垮手表网络 */
        const val MAX_RECIPIENTS: Int = 10
    }
}

/**
 * SMTP 发送客户端。
 *
 * 实现要求：
 * - 支持 AUTH LOGIN / PLAIN（JavaMail 会自动协商）；
 * - 支持 SSL/TLS（465）与 STARTTLS（587）；
 * - 超时 10 秒，发送整体超时 20 秒；
 * - 失败以 `Result.failure(MailError)` 返回，由上层写入草稿并提示重试。
 */
interface SmtpClient {

    /** 测试 SMTP 连接与认证（不发信） */
    suspend fun testConnection(
        account: Account,
        secrets: AccountSecrets,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Result<Unit>

    /** 发送邮件 */
    suspend fun send(
        account: Account,
        secrets: AccountSecrets,
        message: OutgoingMessage,
        timeoutMillis: Int = DEFAULT_SEND_TIMEOUT_MILLIS,
    ): Result<Unit>

    /** 释放资源 */
    fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000
        const val DEFAULT_SEND_TIMEOUT_MILLIS: Int = 20_000
    }
}
