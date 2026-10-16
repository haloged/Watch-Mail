package com.wm.wearmail.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「登录方式」UI 选项（[AuthChoice]）的单元测试。
 *
 * 守护点：登录密码与应用专用密码必须折叠为一个「密码/授权码」选项
 * （两个按钮只会让用户困惑；底层枚举仍分开存储以兼容存量数据），
 * 且顺序稳定、选中判定正确（任何允许的底层认证方式都能落到某个选项上）。
 */
class AuthChoiceTest {

    @Test
    fun `两个密码枚举合并为一个密码选项`() {
        val choices = AuthChoice.forTypes(AuthType.entries)
        assertEquals(2, choices.size)
        assertEquals(listOf("密码/授权码", "OAuth2 令牌"), choices.map { it.label })
        assertEquals(AuthType.APP_PASSWORD, choices[0].authType)
        assertEquals(AuthType.OAUTH2, choices[1].authType)
    }

    @Test
    fun `只有密码类时只显示密码选项`() {
        // 这是「非 Outlook 预设」与扫码页的真实输入（OAuth2 为 Outlook 专属）
        val choices = AuthChoice.forTypes(listOf(AuthType.PASSWORD, AuthType.APP_PASSWORD))
        assertEquals(listOf("密码/授权码"), choices.map { it.label })
    }

    @Test
    fun `Outlook 的仅 OAuth2 列表只显示 OAuth2`() {
        val choices = AuthChoice.forTypes(listOf(AuthType.OAUTH2))
        assertEquals(listOf("OAuth2 令牌"), choices.map { it.label })
    }

    @Test
    fun `密码选项同时匹配两种底层认证方式`() {
        val choices = AuthChoice.forTypes(AuthType.entries)
        val passwordChoice = choices[0]
        assertTrue(passwordChoice.matches(AuthType.PASSWORD))
        assertTrue(passwordChoice.matches(AuthType.APP_PASSWORD))
        assertFalse(passwordChoice.matches(AuthType.OAUTH2))

        val oauth2Choice = choices[1]
        assertTrue(oauth2Choice.matches(AuthType.OAUTH2))
        assertFalse(oauth2Choice.matches(AuthType.APP_PASSWORD))
        assertFalse(oauth2Choice.matches(AuthType.PASSWORD))
    }

    @Test
    fun `每个可选认证方式都有匹配的选项`() {
        // 对每种允许列表，任意底层 authType 都应能落到某个选项上
        // （UI 的 selectedIndex 因此不会出现 -1 的未选中态）
        val allowedLists = listOf(
            AuthType.entries,
            listOf(AuthType.PASSWORD, AuthType.APP_PASSWORD),
            listOf(AuthType.OAUTH2),
        )
        for (allowed in allowedLists) {
            val choices = AuthChoice.forTypes(allowed)
            for (type in allowed) {
                assertTrue(
                    "allowed=$allowed 中 $type 无匹配选项",
                    choices.any { it.matches(type) },
                )
            }
        }
    }
}
