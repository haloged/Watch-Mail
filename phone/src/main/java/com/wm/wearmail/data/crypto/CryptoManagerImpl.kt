package com.wm.wearmail.data.crypto

import com.wm.wearmail.core.Logs
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感信息加解密实现：AES-256-GCM。
 *
 * 格式：`v1:<base64(iv)>:<base64(ciphertext)>`
 * - `v1` 为版本前缀，便于将来更换算法（例如引入密钥轮换）；
 * - IV 由**加密器生成**（Android Keystore 上为 12 字节），随密文一起保存、解密时原样传回；
 * - GCM tag **128 位**：密文被篡改时解密直接失败，能检测完整性；
 * - base64 使用 `java.util.Base64` 而非 `android.util.Base64`，
 *   这样本类可以在**纯 JVM 单元测试**中运行（手表端无 instrumentation 测试）。
 *
 * 与 Android Keystore 的关键约束（真机踩过坑）：
 * Keystore 生成的密钥默认 `setRandomizedEncryptionRequired(true)`，此时**不允许调用方
 * 自带 IV** —— `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))` 会抛
 * `InvalidAlgorithmParameterException`，表现为「保存账户失败」。
 * 因此加密时**不传 IV**，改用 `cipher.iv` 读回 Keystore 生成的 IV；
 * 解密时调用方必须提供 IV（该限制只作用于加密）。
 *
 * 安全红线：
 * - 密码 / OAuth 令牌只以密文形式存在，绝不写日志；
 * - 解密失败只记录「解密失败」这一事实与原因类型，**不记录密文内容**。
 */
class CryptoManagerImpl(private val keyProvider: KeyProvider) : CryptoManager {

    /** 每次加密都重新创建 Cipher：Cipher 实例非线程安全，不可跨线程复用 */
    override fun encrypt(plainText: String): String {
        // 密钥不可用属于致命错误（Keystore 丢失/被篡改），向上抛出由 UI 提示重新登录
        val key = keyProvider.aesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)

        // 不给 init 传 IV：Android Keystore 的随机化加密要求 IV 由 Keystore 生成，
        // 传入调用方 IV 会直接抛 InvalidAlgorithmParameterException。
        cipher.init(Cipher.ENCRYPT_MODE, key)

        // 读回本次加密实际使用的 IV（Android 上为 12 字节；JCE 内存密钥同样可用）
        val iv = cipher.iv ?: throw IllegalStateException("加密器未返回 IV")

        val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return buildString(PREFIX.length + cipherBytes.size * 2 + iv.size * 2) {
            // 注意：版本前缀后必须带分隔符，格式严格为 v1:<base64(iv)>:<base64(ct)>，
            // 否则 decrypt 按 ":" 切分会得到 2 段而不是 3 段（这是单元测试发现的真实缺陷）。
            append(PREFIX)
            append(SEPARATOR)
            append(BASE64.encodeToString(iv))
            append(SEPARATOR)
            append(BASE64.encodeToString(cipherBytes))
        }
    }

    /**
     * 解密 [encrypt] 产生的密文。
     *
     * 以下情况统一返回 null（不抛异常，由调用方决定是否提示用户重新输入凭据）：
     * null / 空串 / 版本前缀不符 / 分段数量不对 / base64 非法 / IV 为空 / GCM 认证失败。
     */
    override fun decrypt(payload: String?): String? {
        if (payload.isNullOrEmpty()) return null
        val parts = payload.split(SEPARATOR)
        if (parts.size != 3 || parts[0] != PREFIX) {
            // 只说明格式不符，绝不回显 payload 内容
            Logs.w(LOG_TAG, "解密失败：密文格式不符或版本不受支持")
            return null
        }
        // 具体解密过程抽到私有方法，便于在需要时直接 return null（不在 try 内嵌套 return）
        return try {
            decryptParts(parts[1], parts[2])
        } catch (t: Throwable) {
            // 覆盖 AEADBadTagException（密文被篡改）、IllegalArgumentException（base64 非法）、
            // IllegalStateException（密钥丢失）等全部情况；不记录密文与异常消息中的敏感片段
            Logs.w(LOG_TAG, "解密失败：${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 解码 IV 与密文并执行 GCM 解密。
     *
     * IV 长度不做严格校验：GCM 允许 1..2^64-1 字节，Android Keystore 实际生成 12 字节，
     * 但不同厂商/版本可能有差异，写死 12 会在个别设备上误判。
     * 空 IV 直接判失败，其余情况由 GCM 认证标签兜底 —— IV 不对必然认证失败并返回 null，
     * 因此放宽长度不会削弱安全性。
     */
    private fun decryptParts(ivBase64: String, cipherBase64: String): String? {
        val iv = BASE64_DECODER.decode(ivBase64)
        if (iv.isEmpty()) {
            Logs.w(LOG_TAG, "解密失败：IV 为空")
            return null
        }
        val cipherBytes = BASE64_DECODER.decode(cipherBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 解密必须由调用方提供 IV（Keystore 的「随机化加密」限制只作用于加密方向）
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.aesKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
    }

    companion object {
        private const val LOG_TAG = "Crypto"

        /** 密文版本前缀 */
        private const val PREFIX = "v1"

        /** 分段分隔符（base64 标准字母表不含 `:`，可安全用作分隔） */
        private const val SEPARATOR = ":"

        /** 变换名称：AES/GCM/NoPadding（GCM 自带认证，无需额外 padding） */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 认证标签长度：128 位 */
        private const val TAG_LENGTH_BITS = 128

        /** JDK 标准 base64（非 android.util.Base64），保证纯 JVM 可测 */
        private val BASE64: Base64.Encoder = Base64.getEncoder()
        private val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()
    }
}
