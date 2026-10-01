package com.wm.wearmail.mail

import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.ProviderPreset
import com.wm.wearmail.model.ProviderPresets
import com.wm.wearmail.model.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * 服务器配置自动探测实现。
 *
 * 手表端手工输入 4 个字段（IMAP 主机/端口、SMTP 主机/端口）几乎不可用，
 * 因此添加账户流程优先走自动探测：先试服务商预设，再试用户提供的线索，
 * 最后按域名推导常见前缀，逐一尝试「端口 × 加密方式」组合。
 *
 * 超时设计：
 * - 总时长上限 [PROBE_TOTAL_TIMEOUT_MILLIS]（30 秒），超过即返回可读的失败提示；
 * - 每个候选的连接/读写超时 [CANDIDATE_TIMEOUT_MILLIS]（10 秒）；
 * - 单候选超时依赖 JavaMail 自身的 socket 超时（阻塞式 socket 读写无法被协程中断，
 *   因此不用 `withTimeout` 包住单个候选，而是在候选之间检查取消）。
 *
 * 日志约束：只记录主机与端口，**绝不记录密码 / OAuth 令牌**。
 */
class ServerProbeImpl(
    private val imap: ImapClient,
    private val smtp: SmtpClient,
) : ServerProbe {

    override fun presetFor(email: String): ProviderPreset = ProviderPresets.detect(email)

    override suspend fun probe(
        email: String,
        secrets: AccountSecrets,
        explicitHost: String?,
    ): Result<ServerConfig> {
        val candidates = candidateConfigs(email, explicitHost)
        Logs.i(
            TAG,
            "开始探测服务器配置：候选=${candidates.size} 组 域名=${domainOf(email)}",
        )

        return try {
            val hit = withTimeout(PROBE_TOTAL_TIMEOUT_MILLIS) {
                var found: ServerConfig? = null
                for (candidate in candidates) {
                    // 协程取消（用户退出页面 / 账户页被销毁）时立即中断探测
                    ensureActive()
                    if (tryCandidate(email, secrets, candidate)) {
                        found = candidate
                        break
                    }
                }
                found
            }

            if (hit != null) {
                Logs.i(
                    TAG,
                    "探测成功：IMAP ${hit.imapHost}:${hit.imapPort}(${hit.imapSecurity.label}) " +
                        "SMTP ${hit.smtpHost}:${hit.smtpPort}(${hit.smtpSecurity.label})",
                )
                Result.success(hit)
            } else {
                Logs.w(TAG, "全部候选配置均不可用（共 ${candidates.size} 组）")
                Result.failure(MailError.Config(NO_CONFIG_MESSAGE))
            }
        } catch (timeout: TimeoutCancellationException) {
            Logs.w(TAG, "服务器探测超过 ${PROBE_TOTAL_TIMEOUT_MILLIS}ms，已中止")
            Result.failure(MailError.Timeout("服务器探测超时，请手动填写配置"))
        } catch (cancel: CancellationException) {
            // 外部取消必须向上传播，不能当作失败返回
            throw cancel
        } catch (t: Throwable) {
            Logs.e(TAG, "服务器探测失败", t)
            Result.failure(t.toMailFailure(NO_CONFIG_MESSAGE))
        }
    }

    override suspend fun verify(
        config: ServerConfig,
        email: String,
        secrets: AccountSecrets,
    ): Result<Unit> {
        val account = accountOf(email, config, authTypeOf(secrets))
        Logs.i(
            TAG,
            "校验配置：IMAP ${config.imapHost}:${config.imapPort} " +
                "SMTP ${config.smtpHost}:${config.smtpPort}",
        )

        val imapResult = imap.testConnection(account, secrets, VERIFY_TIMEOUT_MILLIS)
        val imapError = imapResult.exceptionOrNull()
        if (imapError != null) {
            Logs.w(TAG, "IMAP 校验失败：${config.imapHost}:${config.imapPort}", imapError)
            return Result.failure(imapError.toMailFailure("IMAP 验证失败"))
        }

        val smtpResult = smtp.testConnection(account, secrets, VERIFY_TIMEOUT_MILLIS)
        val smtpError = smtpResult.exceptionOrNull()
        if (smtpError != null) {
            Logs.w(TAG, "SMTP 校验失败：${config.smtpHost}:${config.smtpPort}", smtpError)
            return Result.failure(smtpError.toMailFailure("SMTP 验证失败"))
        }

        Logs.i(TAG, "配置校验通过：$email")
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------
    // 候选矩阵
    // ------------------------------------------------------------------

    /**
     * 依次尝试一个候选：IMAP 与 SMTP 都必须通过才认为命中。
     *
     * IMAP 失败时直接跳过该候选（省掉一次注定无用的 SMTP 建连，手表端省电优先）。
     */
    private suspend fun tryCandidate(
        email: String,
        secrets: AccountSecrets,
        config: ServerConfig,
    ): Boolean {
        val account = accountOf(email, config, authTypeOf(secrets))

        Logs.d(TAG, "尝试 IMAP ${config.imapHost}:${config.imapPort}(${config.imapSecurity.label})")
        val imapResult = imap.testConnection(account, secrets, CANDIDATE_TIMEOUT_MILLIS)
        if (imapResult.isFailure) {
            Logs.d(TAG, "IMAP 不可用：${config.imapHost}:${config.imapPort}")
            return false
        }

        Logs.d(TAG, "尝试 SMTP ${config.smtpHost}:${config.smtpPort}(${config.smtpSecurity.label})")
        val smtpResult = smtp.testConnection(account, secrets, CANDIDATE_TIMEOUT_MILLIS)
        if (smtpResult.isFailure) {
            Logs.d(TAG, "SMTP 不可用：${config.smtpHost}:${config.smtpPort}")
            return false
        }

        return true
    }

    /**
     * 生成候选配置列表（顺序即尝试顺序）：
     * 1. 服务商预设（命中率最高，且端口来自官方文档）；
     * 2. 用户显式提供的主机：`imap.<host>` / `smtp.<host>`，以及该主机同时作为两端；
     * 3. 由邮箱域名推导：`imap.<domain>` / `smtp.<domain>` 与 `mail.<domain>`。
     *
     * 每个主机对都会展开为 {993/SSL, 143/STARTTLS} × {465/SSL, 587/STARTTLS} 四种组合。
     */
    private fun candidateConfigs(email: String, explicitHost: String?): List<ServerConfig> {
        val candidates = ArrayList<ServerConfig>()

        val preset = ProviderPresets.detect(email).config
        if (preset.imapHost.isNotBlank() && preset.smtpHost.isNotBlank()) {
            candidates += preset
        }

        val explicit = explicitHost?.trim()?.lowercase().orEmpty()
        if (explicit.isNotEmpty()) {
            // 用户可能填 "corp.com"，也可能填 "imap.corp.com"，统一还原出裸域名
            val bare = explicit.removePrefix("imap.").removePrefix("smtp.")
            addHostPair(candidates, "imap.$bare", "smtp.$bare")
            addHostPair(candidates, bare, bare)
        }

        val domain = domainOf(email)
        if (domain.isNotEmpty()) {
            addHostPair(candidates, "imap.$domain", "smtp.$domain")
            addHostPair(candidates, "mail.$domain", "mail.$domain")
        }

        return candidates
    }

    /** 为一个主机对补齐所有「端口 × 加密方式」组合，并按插入顺序去重 */
    private fun addHostPair(candidates: MutableList<ServerConfig>, imapHost: String, smtpHost: String) {
        if (imapHost.isBlank() || smtpHost.isBlank()) return
        for ((imapPort, imapSecurity) in IMAP_ENDPOINTS) {
            for ((smtpPort, smtpSecurity) in SMTP_ENDPOINTS) {
                val config = ServerConfig(
                    imapHost = imapHost,
                    imapPort = imapPort,
                    imapSecurity = imapSecurity,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    smtpSecurity = smtpSecurity,
                )
                if (candidates.none { it == config }) candidates += config
            }
        }
    }

    private fun domainOf(email: String): String =
        email.substringAfterLast('@', "").trim().lowercase()

    /** 存在 OAuth2 令牌组时按 OAUTH2 认证，否则按应用专用密码 */
    private fun authTypeOf(secrets: AccountSecrets): AuthType =
        if (secrets.oauth != null) AuthType.OAUTH2 else AuthType.APP_PASSWORD

    /** 构造仅用于「连通性验证」的临时账户（不入库，不含任何凭据） */
    private fun accountOf(email: String, config: ServerConfig, authType: AuthType): Account =
        Account(
            email = email,
            authType = authType,
            imapHost = config.imapHost,
            imapPort = config.imapPort,
            imapSecurity = config.imapSecurity,
            smtpHost = config.smtpHost,
            smtpPort = config.smtpPort,
            smtpSecurity = config.smtpSecurity,
        )

    private companion object {
        const val TAG = "ServerProbe"

        /** 探测总时长上限：需求规定约 30 秒 */
        const val PROBE_TOTAL_TIMEOUT_MILLIS = 30_000L

        /** 单个候选的连接/读写超时：10 秒 */
        const val CANDIDATE_TIMEOUT_MILLIS = 10_000

        /** 保存前的最终校验超时：给两端各 10 秒 */
        const val VERIFY_TIMEOUT_MILLIS = 10_000

        const val NO_CONFIG_MESSAGE = "未能自动探测到服务器配置，请手动填写"

        /** IMAP 常见端口组合：993 全程 TLS、143 + STARTTLS */
        val IMAP_ENDPOINTS = listOf(
            993 to MailSecurity.SSL_TLS,
            143 to MailSecurity.STARTTLS,
        )

        /** SMTP 常见端口组合：465 全程 TLS、587 + STARTTLS */
        val SMTP_ENDPOINTS = listOf(
            465 to MailSecurity.SSL_TLS,
            587 to MailSecurity.STARTTLS,
        )
    }
}
