package com.wm.wearmail.mail

import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.ProviderPreset
import com.wm.wearmail.model.ServerConfig

/**
 * 服务器配置自动探测。
 *
 * 手表端手动输入 4 个字段（IMAP 主机/端口、SMTP 主机/端口）成本极高，
 * 因此「添加账户」流程优先走自动探测：先按域名匹配服务商预设，
 * 再用候选矩阵逐一尝试连接与认证，命中后直接回填表单。
 *
 * 候选矩阵（按命中概率排序）：
 *  1. 预设配置（如 imap.qq.com:993 + smtp.qq.com:465）
 *  2. 由邮箱域名推导：`imap.<domain>` / `smtp.<domain>` × {993/SSL, 143/STARTTLS}
 *     / {465/SSL, 587/STARTTLS, 25/STARTTLS}
 *  3. 企业常见前缀：`mail.<domain>`、`exchange.<domain>`
 */
interface ServerProbe {

    /** 依据域名匹配服务商预设（同步、纯本地计算） */
    fun presetFor(email: String): ProviderPreset

    /**
     * 自动探测可用的服务器配置。
     *
     * 探测过程必须能被打断（协程取消），且总耗时不超过 ~30 秒；
     * 全部候选失败时返回 [MailError.Config]。
     */
    suspend fun probe(
        email: String,
        secrets: AccountSecrets,
        explicitHost: String? = null,
    ): Result<ServerConfig>

    /**
     * 校验一份完整配置是否真的可用（IMAP 与 SMTP 都需通过认证）。
     * 用于「添加账户」页保存前的最终验证。
     */
    suspend fun verify(
        config: ServerConfig,
        email: String,
        secrets: AccountSecrets,
    ): Result<Unit>
}
