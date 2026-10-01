package com.wm.wearmail.pairing

import com.wm.wearmail.model.ProviderPreset
import com.wm.wearmail.model.ProviderPresets

/**
 * 配对表单的服务器地址兜底（纯函数，可单元测试）。
 *
 * 背景（这曾经导致「手机扫码提交失败」）：
 * 网页里的 IMAP / SMTP 主机输入框没有默认值，完全依赖内联 JS 在
 * 「选择服务商」或「邮箱失焦」时填充；一旦 JS 那一步没生效，
 * 或者用户邮箱域名不在预设表里（企业邮箱、自建域名、iCloud、sina 等），
 * 主机就会是空串，提交后只会得到一句笼统的「提交失败」。
 *
 * 因此服务端再兜一次底：
 * 1. 表单填全了就完全尊重表单；
 * 2. 没填全时，优先用用户**显式选择**的服务商，其次按邮箱域名识别；
 * 3. 仍然拿不到（企业/自建邮箱）才返回 null，由调用方给出**明确**提示。
 */
object PairingFormDefaults {

    /**
     * @param imapHost 最终使用的 IMAP 主机
     * @param smtpHost 最终使用的 SMTP 主机
     * @param presetId 兜底时采用的服务商 id（未兜底时为表单里选中的 id，可能为 null）
     * @param usedFallback 是否发生了兜底（用于日志与提示）
     */
    data class Resolved(
        val imapHost: String,
        val smtpHost: String,
        val presetId: String?,
        val usedFallback: Boolean,
    )

    /** 预设是否同时具备可用的 IMAP 与 SMTP 主机 */
    private fun ProviderPreset.isUsable(): Boolean =
        config.imapHost.isNotBlank() && config.smtpHost.isNotBlank()

    fun resolve(
        email: String,
        formImapHost: String,
        formSmtpHost: String,
        formPresetId: String? = null,
    ): Resolved? {
        val imap = formImapHost.trim()
        val smtp = formSmtpHost.trim()
        val selectedId = formPresetId?.trim()?.takeIf { it.isNotEmpty() }

        // 1) 表单填全：直接采用，不做任何猜测
        if (imap.isNotEmpty() && smtp.isNotEmpty()) {
            return Resolved(imapHost = imap, smtpHost = smtp, presetId = selectedId, usedFallback = false)
        }

        // 2) 兜底：显式选择的服务商优先，其次按域名识别
        val selected = selectedId?.let { ProviderPresets.byId(it) }
        val detected = ProviderPresets.detect(email)
        val preset: ProviderPreset = when {
            selected != null && selected.isUsable() -> selected
            detected.isUsable() -> detected
            else -> return null
        }

        val resolvedImap = imap.ifEmpty { preset.config.imapHost }
        val resolvedSmtp = smtp.ifEmpty { preset.config.smtpHost }
        if (resolvedImap.isBlank() || resolvedSmtp.isBlank()) return null

        return Resolved(
            imapHost = resolvedImap,
            smtpHost = resolvedSmtp,
            presetId = preset.id,
            usedFallback = true,
        )
    }
}
