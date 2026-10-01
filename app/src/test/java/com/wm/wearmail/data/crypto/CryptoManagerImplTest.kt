package com.wm.wearmail.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [CryptoManagerImpl] 的纯 JVM 单元测试。
 *
 * 依赖注入 [KeyProvider] 的内存假实现，因此**不需要 Android 运行时**
 * （不用 Robolectric / instrumentation）：验证的是 AES-256-GCM 加解密与
 * 密文格式契约本身。
 */
class CryptoManagerImplTest {

    /** 内存密钥假实现：测试期间复用同一把密钥，保证「加密→解密」可还原 */
    private val keyProvider = object : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun aesKey(): SecretKey = key
    }

    private val crypto: CryptoManager = CryptoManagerImpl(keyProvider)

    @Test
    fun `加密结果不等于明文且带 v1 前缀`() {
        val plain = "hunter2-应用专用密码"
        val payload = crypto.encrypt(plain)

        assertNotEquals(plain, payload)
        assertTrue("密文必须以 v1: 开头，实际=$payload", payload.startsWith("v1:"))
        // 格式固定为 v1:<base64(iv)>:<base64(ct)>，恰好两处分隔符
        assertEquals("密文分段数量应为 3", 3, payload.split(":").size)
    }

    @Test
    fun `同一明文两次加密密文不同（随机 IV）`() {
        val plain = "same-secret"

        val first = crypto.encrypt(plain)
        val second = crypto.encrypt(plain)

        assertNotEquals("两次加密必须因随机 IV 而不同", first, second)
        // 但两者都能正确解密回同一明文
        assertEquals(plain, crypto.decrypt(first))
        assertEquals(plain, crypto.decrypt(second))
    }

    @Test
    fun `解密可还原 ASCII 明文`() {
        val plain = "user@example.com:app-password-123"

        assertEquals(plain, crypto.decrypt(crypto.encrypt(plain)))
    }

    @Test
    fun `解密可还原中文与 emoji`() {
        val plain = "密码🔐含中文、emoji 😀 与换行\n第二行"

        assertEquals(plain, crypto.decrypt(crypto.encrypt(plain)))
    }

    @Test
    fun `解密可还原超长字符串`() {
        val plain = "长".repeat(20_000) + "tail"

        val payload = crypto.encrypt(plain)
        val restored = crypto.decrypt(payload)

        assertEquals(plain, restored)
        // 加密后长度只比明文多出固定开销（IV + tag + base64 膨胀），不会二次膨胀
        assertEquals(plain.length, restored?.length)
    }

    @Test
    fun `解密 null 返回 null`() {
        assertNull(crypto.decrypt(null))
    }

    @Test
    fun `解密空串返回 null`() {
        assertNull(crypto.decrypt(""))
    }

    @Test
    fun `解密乱码返回 null`() {
        assertNull(crypto.decrypt("乱码"))
    }

    @Test
    fun `解密版本前缀不完整的内容返回 null`() {
        assertNull(crypto.decrypt("v1:xxx"))
    }

    @Test
    fun `解密版本号不支持返回 null`() {
        val payload = crypto.encrypt("secret")
        val tampered = "v2" + payload.removePrefix("v1")

        assertNull(crypto.decrypt(tampered))
    }

    @Test
    fun `解密 base64 非法返回 null`() {
        assertNull(crypto.decrypt("v1:!!!!:????"))
    }

    @Test
    fun `解密 IV 非 12 字节时由 GCM 认证失败返回 null`() {
        // 合法 base64，但 IV 只有 4 字节：GCM 允许该长度，认证必然失败 → 返回 null。
        // （不写死 12 字节校验：不同厂商的 Keystore 生成长度可能有差异，
        //   安全性由 GCM 认证标签保证，见 CryptoManagerImpl.decryptParts 注释）
        assertNull(crypto.decrypt("v1:AQIDBA==:YWJjZA=="))
    }

    @Test
    fun `解密空 IV 返回 null`() {
        assertNull(crypto.decrypt("v1::YWJjZA=="))
    }

    @Test
    fun `篡改密文后解密返回 null（GCM 认证失败）`() {
        val payload = crypto.encrypt("important-secret")
        val parts = payload.split(":")

        // 翻转密文最后一字节，触发 AEADBadTagException
        val raw = java.util.Base64.getDecoder().decode(parts[2])
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tamperedCipher = java.util.Base64.getEncoder().encodeToString(raw)

        assertNull(crypto.decrypt("${parts[0]}:${parts[1]}:$tamperedCipher"))
    }

    @Test
    fun `篡改 IV 后解密返回 null`() {
        val payload = crypto.encrypt("important-secret")
        val parts = payload.split(":")

        val iv = java.util.Base64.getDecoder().decode(parts[1])
        iv[0] = (iv[0].toInt() xor 0xFF).toByte()
        val tamperedIv = java.util.Base64.getEncoder().encodeToString(iv)

        assertNull(crypto.decrypt("${parts[0]}:$tamperedIv:${parts[2]}"))
    }

    @Test
    fun `密文中不包含明文子串`() {
        val plain = "P@ssw0rd-Secret-Token"

        val payload = crypto.encrypt(plain)

        assertFalse("密文不得包含明文", payload.contains(plain))
        // 同时不应包含明文的 base64 编码形式（避免「只是换了个编码」的假加密）
        val base64Plain = java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        assertFalse("密文不得包含明文的 base64 形式", payload.contains(base64Plain))
    }

    @Test
    fun `encryptNullable 对 null 与空串返回 null 而非抛异常`() {
        assertNull(crypto.encryptNullable(null))
        assertNull(crypto.encryptNullable(""))

        val payload = crypto.encryptNullable("token")
        assertTrue(payload != null && payload.startsWith("v1:"))
        assertEquals("token", crypto.decrypt(payload))
    }

    @Test
    fun `密钥不可用时 encrypt 抛出 IllegalStateException`() {
        val broken = CryptoManagerImpl(object : KeyProvider {
            override fun aesKey(): SecretKey = throw IllegalStateException("keystore 不可用")
        })

        try {
            broken.encrypt("secret")
            throw AssertionError("密钥不可用时必须抛出 IllegalStateException")
        } catch (expected: IllegalStateException) {
            // 预期路径
        }
    }

    @Test
    fun `密钥不可用时 decrypt 返回 null 而不抛异常`() {
        val broken = CryptoManagerImpl(object : KeyProvider {
            override fun aesKey(): SecretKey = throw IllegalStateException("keystore 不可用")
        })
        // 先用正常密钥造一段合法密文
        val payload = crypto.encrypt("secret")

        assertNull(broken.decrypt(payload))
    }
}
