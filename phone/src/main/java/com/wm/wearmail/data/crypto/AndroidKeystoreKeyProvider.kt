package com.wm.wearmail.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wm.wearmail.core.Logs
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 基于 Android Keystore 的密钥提供者。
 *
 * 安全设计：
 * - 别名固定为 [KEY_ALIAS]，密钥材料由 TEE/StrongBox 持有，**无法被导出**
 *   （应用只能拿到句柄，即使 APK 被反编译也拿不到密钥）；
 * - `setUserAuthenticationRequired(false)`：手表端抬腕即用，若强制生物识别
 *   会在息屏后台同步时无法解密，因此不绑定用户认证；
 * - 密钥丢失（恢复出厂设置 / 清除应用数据 / 设备迁移）会导致旧密文永久不可解，
 *   此时 [com.wm.wearmail.data.repo.AccountRepositoryImpl.secrets] 返回 null，
 *   由 UI 引导用户重新输入密码。
 *
 * 日志约束：只记录别名与操作结果，绝不记录任何密钥材料或密文内容。
 */
class AndroidKeystoreKeyProvider(private val context: Context) : KeyProvider {

    /**
     * 获取（必要时生成）AES-256-GCM 主密钥。
     *
     * @throws IllegalStateException Keystore 不可用或密钥生成失败
     */
    override fun aesKey(): SecretKey = synchronized(LOCK) {
        try {
            val keyStore = loadKeyStore()
            // 先尝试读取已有密钥；getKey 返回的类型必须显式判空，避免 ClassCastException
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                return@synchronized existing
            }
            Logs.i(TAG, "主密钥不存在，开始生成（别名 $KEY_ALIAS）")
            val generated = generateKey()
            Logs.i(TAG, "主密钥生成完成")
            generated
        } catch (t: Throwable) {
            // 不打印任何密钥材料；仅说明失败事实，交由上层决定提示用户重新登录
            Logs.e(TAG, "获取 Android Keystore 主密钥失败", t)
            throw IllegalStateException("Android Keystore 主密钥不可用", t)
        }
    }

    /** 打开 AndroidKeyStore 实例（provider 未注册时 getInstance 会抛异常） */
    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** 生成 AES-256-GCM 主密钥并写入 Keystore */
    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        Logs.d(TAG, "密钥生成上下文：${context.packageName}")
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "Keystore"

        /** Android Keystore 提供者名（系统内置） */
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** 主密钥别名：全应用唯一 */
        const val KEY_ALIAS = "wearmail_master_key"

        /** AES-256（GCM 模式） */
        private const val KEY_SIZE_BITS = 256

        /** Keystore 操作加锁：避免并发 getKey/generateKey 造成重复生成 */
        private val LOCK = Any()
    }
}
