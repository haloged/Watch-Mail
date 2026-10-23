package com.wm.wearmail.mail

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 邮件协议层统一错误类型。
 *
 * 为什么要自定义而不是直接抛 JavaMail 的异常：
 * 1. JavaMail 异常类型繁多（MessagingException / AuthenticationFailedException /
 *    FolderClosedException ...），UI 层无法逐个处理；
 * 2. 手表屏幕小，必须给出简洁的中文提示；
 * 3. 便于同步引擎统一决策「重试 / 放弃 / 提示重新登录」。
 */
sealed class MailError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 网络不可达、DNS 失败、连接被拒绝等 */
    class Network(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** 认证失败：密码/授权码错误，或服务商要求专用密码 */
    class Auth(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** 连接或读写超时 */
    class Timeout(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** 协议错误：服务器返回了非预期响应 */
    class Protocol(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** 配置错误：服务器地址/端口/加密方式不正确 */
    class Config(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** 未知错误兜底 */
    class Unknown(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** 面向用户的中文提示（不含服务器内部细节，避免信息过载） */
    val userMessage: String
        get() = when (this) {
            is Network -> "网络不可用，已展示本地缓存"
            is Auth -> "账号或密码/授权码错误，请重新配置"
            is Timeout -> "连接超时（超过 10 秒），请检查网络"
            is Protocol -> "服务器响应异常，请稍后重试"
            is Config -> "服务器地址或端口配置有误"
            is Unknown -> "同步失败：${message?.take(60) ?: "未知错误"}"
        }

    /** 该错误是否值得自动重试（指数退避） */
    val isRetryable: Boolean
        get() = when (this) {
            is Network, is Timeout, is Protocol, is Unknown -> true
            // 认证与配置错误重试无意义，必须由用户修正
            is Auth, is Config -> false
        }

    companion object {
        /** 无网络时的标准错误 */
        fun offline(): MailError = Network("设备当前无网络连接")
    }
}

/**
 * 把底层异常归一化为 [MailError]。
 *
 * 判定顺序很关键：先看是否为 JavaMail 的认证失败，再看网络层异常，
 * 最后做字符串兜底匹配（部分服务商只返回文本错误码）。
 */
fun Throwable.toMailError(defaultMessage: String = "邮件操作失败"): MailError {
    if (this is MailError) return this

    val text = message.orEmpty()
    val lower = text.lowercase()

    return when {
        this is AuthenticationFailedExceptionWrapper -> MailError.Auth(text, this)

        lower.contains("authenticationfailed") ||
            lower.contains("auth") && lower.contains("fail") ||
            lower.contains("invalid credentials") ||
            lower.contains("login fail") ||
            lower.contains("授权码") -> MailError.Auth(text.ifBlank { "认证失败" }, this)

        this is UnknownHostException -> MailError.Network("无法解析服务器地址：$text", this)

        this is SocketTimeoutException -> MailError.Timeout("连接超时", this)

        this is SSLException ->
            MailError.Config("TLS 握手失败，请检查加密方式与端口：$text", this)

        lower.contains("timeout") || lower.contains("timed out") -> MailError.Timeout(text, this)

        lower.contains("connection refused") ||
            lower.contains("network is unreachable") ||
            lower.contains("unable to connect") ||
            lower.contains("connect fail") -> MailError.Network(text, this)

        lower.contains("unknown host") ||
            lower.contains("no address associated") -> MailError.Network(text, this)

        else -> MailError.Unknown(text.ifBlank { defaultMessage }, this)
    }
}

/**
 * 标记接口：由协议实现层在捕获到 JavaMail 的
 * `javax.mail.AuthenticationFailedException` 后包装抛出，
 * 从而让 [toMailError] 无需在基础层直接依赖 JavaMail 类型。
 */
class AuthenticationFailedExceptionWrapper(message: String, cause: Throwable? = null) :
    Exception(message, cause)
