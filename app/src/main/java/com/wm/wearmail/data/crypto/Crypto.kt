package com.wm.wearmail.data.crypto

import javax.crypto.SecretKey

/**
 * 对称密钥提供者。
 *
 * 抽象出来的目的：
 * 1. 生产实现使用 Android Keystore（密钥永不离开 TEE/StrongBox，无法被导出）；
 * 2. 单元测试可用内存密钥实现，从而在纯 JVM 环境验证加解密正确性。
 */
interface KeyProvider {
    /** 获取用于 AES-256-GCM 的密钥 */
    fun aesKey(): SecretKey
}

/**
 * 敏感信息加解密门面。
 *
 * 安全要求（需求 3.3 / 代码要求）：
 * - 密码、OAuth 令牌等**绝不**明文落盘；
 * - 采用 AES-256-GCM（带认证的加密，可检测密文被篡改）；
 * - 每次加密使用新的随机 IV，密文格式为 `v1:<base64(iv)>:<base64(cipher)>`。
 */
interface CryptoManager {

    /**
     * 加密明文。
     *
     * @throws IllegalStateException 密钥不可用时
     */
    fun encrypt(plainText: String): String

    /**
     * 解密 [CryptoManager.encrypt] 产生的密文。
     *
     * 解密失败（密钥丢失、密文损坏、格式不符）时返回 null，
     * 由调用方决定是提示用户重新输入凭据还是忽略该账户。
     */
    fun decrypt(payload: String?): String?

    /** 便捷方法：null / 空串原样返回，避免调用方频繁判空 */
    fun encryptNullable(plainText: String?): String? =
        if (plainText.isNullOrEmpty()) null else encrypt(plainText)
}
