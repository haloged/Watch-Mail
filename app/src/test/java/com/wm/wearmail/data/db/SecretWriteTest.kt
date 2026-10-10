package com.wm.wearmail.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [SecretWrite] 的单元测试（纯 JVM，无需设备）。
 *
 * 守护的核心契约（曾导致 P0 缺陷）：
 * 「不改动凭据」（[SecretWrite.Keep]）与「显式清空凭据」（`Set(null)`）
 * 必须是两个**不同的值** —— 早期实现用同一个 null 表达两者，
 * 让「只改别名」的重命名流程把加密后的密码与 OAuth 令牌一并清空了。
 */
class SecretWriteTest {

    @Test
    fun `不更新凭据时一律 Keep，即使带了新值`() {
        assertEquals(SecretWrite.Keep, SecretWrite.of(update = false, value = null))
        // update=false 表示「本次不碰凭据列」，传了什么值都不该被写入
        assertEquals(SecretWrite.Keep, SecretWrite.of(update = false, value = "enc:v1:xx"))
    }

    @Test
    fun `更新凭据且值为 null 表示显式清空`() {
        assertEquals(SecretWrite.Set(null), SecretWrite.of(update = true, value = null))
    }

    @Test
    fun `更新凭据且带值表示写入新密文`() {
        assertEquals(SecretWrite.Set("enc:v1:abc"), SecretWrite.of(update = true, value = "enc:v1:abc"))
    }

    @Test
    fun `Keep 与 Set null 是不同语义`() {
        // 这条断言是修复的立足点：两者若可互换，「重命名账户」又会静默清空凭据
        assertNotEquals(SecretWrite.Keep, SecretWrite.Set(null))
    }

    @Test
    fun `Set 按值相等，不同值不相等`() {
        assertEquals(SecretWrite.Set("x"), SecretWrite.Set("x"))
        assertNotEquals(SecretWrite.Set("x"), SecretWrite.Set("y"))
        // data object：Keep 全局唯一实例（与 Set 永远不等）
        assertNotEquals(SecretWrite.Keep, SecretWrite.Set("x"))
    }
}
