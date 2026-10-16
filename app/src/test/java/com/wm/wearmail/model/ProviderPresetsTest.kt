package com.wm.wearmail.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `OAuth2 是 Outlook 专属，其余预设不含`() {
        val passwordTypes = listOf(AuthType.PASSWORD, AuthType.APP_PASSWORD)
        for (preset in ProviderPresets.all.filter { it.id != ProviderPresets.OUTLOOK.id }) {
            assertEquals(
                "预设 ${preset.id} 应只提供密码/授权码",
                passwordTypes,
                ProviderPresets.authTypesFor(preset.id),
            )
        }
    }

    @Test
    fun `未知预设 id 回退到密码类认证方式`() {
        // byId 对未知 id 回退到 ENTERPRISE，authTypesFor 同样不应崩溃或返回空列表
        val passwordTypes = listOf(AuthType.PASSWORD, AuthType.APP_PASSWORD)
        assertEquals(passwordTypes, ProviderPresets.authTypesFor("unknown-provider"))
        assertEquals(passwordTypes, ProviderPresets.authTypesFor(""))
    }

    @Test
    fun `每个预设的认证方式列表非空（selectPreset 依赖 first 兜底）`() {
        for (preset in ProviderPresets.all) {
            val types = ProviderPresets.authTypesFor(preset.id)
            assertTrue("预设 ${preset.id} 的认证方式列表不能为空", types.isNotEmpty())
        }
    }

    @Test
    fun `识别微软个人邮箱域`() {
        assertTrue(ProviderPresets.isMicrosoftMailbox("user@outlook.com"))
        assertTrue(ProviderPresets.isMicrosoftMailbox("USER@Hotmail.com"))
        assertTrue(ProviderPresets.isMicrosoftMailbox("a@live.com"))
        assertTrue(ProviderPresets.isMicrosoftMailbox("a@msn.com"))
        // 子域（如 mail.outlook.com）按后缀识别
        assertTrue(ProviderPresets.isMicrosoftMailbox("a@mail.outlook.com"))
    }

    @Test
    fun `非微软邮箱不会误判`() {
        assertFalse(ProviderPresets.isMicrosoftMailbox("user@gmail.com"))
        assertFalse(ProviderPresets.isMicrosoftMailbox("user@qq.com"))
        assertFalse(ProviderPresets.isMicrosoftMailbox("user@163.com"))
        assertFalse(ProviderPresets.isMicrosoftMailbox("user@company.com"))
        assertFalse(ProviderPresets.isMicrosoftMailbox(""))
        assertFalse(ProviderPresets.isMicrosoftMailbox("not-an-email"))
    }
}
