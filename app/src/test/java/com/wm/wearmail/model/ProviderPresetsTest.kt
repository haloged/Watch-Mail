package com.wm.wearmail.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商预设能力的单元测试。
 *
 * 重点守护 [ProviderPresets.authTypesFor]：Outlook / Office 365 已停用
 * IMAP/SMTP 基本认证，只能使用 OAuth2 —— 这是服务商侧的硬约束，
 * 不能因为 UI 调整而把密码/应用密码选项重新放出来。
 */
class ProviderPresetsTest {

    @Test
    fun `Outlook 只支持 OAuth2`() {
        assertEquals(
            listOf(AuthType.OAUTH2),
            ProviderPresets.authTypesFor(ProviderPresets.OUTLOOK.id),
        )
    }

    @Test
    fun `其余预设保留全部认证方式`() {
        val all = AuthType.entries.toList()
        for (preset in ProviderPresets.all.filter { it.id != ProviderPresets.OUTLOOK.id }) {
            assertEquals("预设 ${preset.id} 不应被收窄", all, ProviderPresets.authTypesFor(preset.id))
        }
    }

    @Test
    fun `未知预设 id 回退到全部认证方式`() {
        // byId 对未知 id 回退到 ENTERPRISE，authTypesFor 同样不应崩溃或返回空列表
        assertEquals(AuthType.entries.toList(), ProviderPresets.authTypesFor("unknown-provider"))
        assertEquals(AuthType.entries.toList(), ProviderPresets.authTypesFor(""))
    }

    @Test
    fun `每个预设的认证方式列表非空（selectPreset 依赖 first 兜底）`() {
        for (preset in ProviderPresets.all) {
            val types = ProviderPresets.authTypesFor(preset.id)
            assertTrue("预设 ${preset.id} 的认证方式列表不能为空", types.isNotEmpty())
        }
    }
}
