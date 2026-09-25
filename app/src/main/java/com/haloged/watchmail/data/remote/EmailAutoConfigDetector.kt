package com.haloged.watchmail.data.remote

import android.util.Log
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.local.entity.EncryptionType
import com.haloged.watchmail.util.EmailConfigUtil
import com.haloged.watchmail.util.EmailServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.Session
import javax.mail.Store

/**
 * 邮箱配置自动探测结果
 */
sealed class AutoConfigResult {
    data class Success(val config: EmailServerConfig) : AutoConfigResult()
    data class Error(val message: String) : AutoConfigResult()
}

/**
 * 邮箱配置自动探测器
 * 尝试自动发现邮箱服务器配置
 */
class EmailAutoConfigDetector {
    
    companion object {
        private const val TAG = "EmailAutoConfigDetector"
        private const val CONNECTION_TIMEOUT = 5000 // 5秒超时
    }
    
    /**
     * 自动探测邮箱配置
     * @param email 邮箱地址
     * @return 探测结果
     */
    suspend fun detectConfig(email: String): AutoConfigResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始探测邮箱配置: $email")
            
            val domain = EmailConfigUtil.getEmailDomain(email)
            
            // 1. 首先检查已知配置
            val knownConfig = EmailConfigUtil.getConfigForEmail(email)
            if (knownConfig != null) {
                Log.d(TAG, "使用已知配置: $domain")
                return@withContext AutoConfigResult.Success(knownConfig)
            }
            
            // 2. 尝试常见配置
            val commonConfigs = generateCommonConfigs(domain)
            
            for (config in commonConfigs) {
                if (testImapConnection(email, config)) {
                    Log.d(TAG, "找到可用配置: ${config.imapHost}:${config.imapPort}")
                    return@withContext AutoConfigResult.Success(config)
                }
            }
            
            // 3. 尝试SRV记录查询（如果需要更复杂的探测）
            // 这里简化处理，返回通用配置
            val genericConfig = EmailConfigUtil.generateGenericConfig(domain)
            Log.d(TAG, "使用通用配置: ${genericConfig.imapHost}")
            
            AutoConfigResult.Success(genericConfig)
            
        } catch (e: Exception) {
            Log.e(TAG, "配置探测失败: $email", e)
            AutoConfigResult.Error("无法自动探测邮箱配置: ${e.message}")
        }
    }
    
    /**
     * 生成常见配置组合
     */
    private fun generateCommonConfigs(domain: String): List<EmailServerConfig> {
        return listOf(
            // SSL 993/465
            EmailServerConfig(
                imapHost = "imap.$domain",
                imapPort = 993,
                imapEncryption = EncryptionType.SSL,
                smtpHost = "smtp.$domain",
                smtpPort = 465,
                smtpEncryption = EncryptionType.SSL
            ),
            // STARTTLS 993/587
            EmailServerConfig(
                imapHost = "imap.$domain",
                imapPort = 993,
                imapEncryption = EncryptionType.STARTTLS,
                smtpHost = "smtp.$domain",
                smtpPort = 587,
                smtpEncryption = EncryptionType.STARTTLS
            ),
            // mail.前缀
            EmailServerConfig(
                imapHost = "mail.$domain",
                imapPort = 993,
                imapEncryption = EncryptionType.SSL,
                smtpHost = "mail.$domain",
                smtpPort = 587,
                smtpEncryption = EncryptionType.STARTTLS
            ),
            // 非加密 143/25
            EmailServerConfig(
                imapHost = "imap.$domain",
                imapPort = 143,
                imapEncryption = EncryptionType.NONE,
                smtpHost = "smtp.$domain",
                smtpPort = 25,
                smtpEncryption = EncryptionType.NONE
            )
        )
    }
    
    /**
     * 测试IMAP连接是否可用
     */
    private fun testImapConnection(email: String, config: EmailServerConfig): Boolean {
        var store: Store? = null
        try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", config.imapHost)
                put("mail.imaps.port", config.imapPort.toString())
                put("mail.imaps.connectiontimeout", CONNECTION_TIMEOUT.toString())
                put("mail.imaps.timeout", CONNECTION_TIMEOUT.toString())
                
                when (config.imapEncryption) {
                    EncryptionType.SSL, EncryptionType.TLS -> {
                        put("mail.imaps.ssl.enable", "true")
                        put("mail.imaps.ssl.trust", config.imapHost)
                    }
                    EncryptionType.STARTTLS -> {
                        put("mail.imaps.starttls.enable", "true")
                        put("mail.imaps.ssl.trust", config.imapHost)
                    }
                    EncryptionType.NONE -> {
                        put("mail.imaps.ssl.enable", "false")
                    }
                }
            }
            
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            
            // 只测试连接，不登录
            store.connect(config.imapHost, config.imapPort, null, null)
            
            return true
        } catch (e: Exception) {
            // 连接失败，尝试下一个配置
            Log.d(TAG, "连接测试失败: ${config.imapHost}:${config.imapPort} - ${e.message}")
            return false
        } finally {
            try {
                store?.close()
            } catch (e: Exception) {
                // 忽略关闭错误
            }
        }
    }
}
