package com.wm.wearmail.core

import android.util.Log

/**
 * 统一日志门面。
 *
 * 设计取舍：
 * - 手表端 logcat 抓取不便，因此所有日志都带模块前缀 `WearMail/xxx`，便于过滤；
 * - 严禁在日志中输出密码、OAuth 令牌、邮件正文等敏感信息，只允许输出
 *   服务器地址、UID、状态码等排障必需的非敏感信息。
 */
object Logs {

    private const val PREFIX = "WearMail"

    /** 是否输出调试日志（release 包由 R8 直接内联去掉，无运行时开销） */
    private val debugEnabled: Boolean = BuildConfigCompat.DEBUG

    fun d(tag: String, message: String) {
        if (debugEnabled) {
            Log.d("$PREFIX/$tag", message)
        }
    }

    fun i(tag: String, message: String) {
        Log.i("$PREFIX/$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w("$PREFIX/$tag", message)
        } else {
            Log.w("$PREFIX/$tag", message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e("$PREFIX/$tag", message)
        } else {
            Log.e("$PREFIX/$tag", message, throwable)
        }
    }
}

/**
 * BuildConfig 间接引用：把对生成类的依赖集中到一处，
 * 便于单元测试（纯 JVM）环境下替换。
 */
internal object BuildConfigCompat {
    val DEBUG: Boolean = try {
        com.wm.wearmail.BuildConfig.DEBUG
    } catch (t: Throwable) {
        // 单元测试环境没有生成 BuildConfig 时退化为 true
        true
    }
}
