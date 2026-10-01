package com.wm.wearmail.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackoffPolicy] 单元测试。
 *
 * 抖动使用 `kotlin.random.Random`（无注入点，构造签名由契约冻结），
 * 因此延迟相关的断言全部采用「区间断言」而不是固定值；
 * 需要精确值的场景把 `jitterRatio` 设为 0。
 */
class BackoffTest {

    // ---------------- delayMillisFor ----------------

    @Test
    fun `attempt0 返回初始延迟加减抖动`() {
        val policy = BackoffPolicy()
        repeat(100) {
            val delay = policy.delayMillisFor(0)
            assertTrue("delay=$delay 应落在 [800,1200]", delay in 800L..1_200L)
        }
    }

    @Test
    fun `延迟按 multiplier 指数增长`() {
        val policy = BackoffPolicy()
        repeat(50) {
            assertTrue(policy.delayMillisFor(1) in 1_600L..2_400L)
            assertTrue(policy.delayMillisFor(2) in 3_200L..4_800L)
            assertTrue(policy.delayMillisFor(3) in 6_400L..9_600L)
        }
    }

    @Test
    fun `抖动为 0 时延迟为精确值`() {
        val policy = BackoffPolicy(jitterRatio = 0.0)
        assertEquals(1_000L, policy.delayMillisFor(0))
        assertEquals(2_000L, policy.delayMillisFor(1))
        assertEquals(4_000L, policy.delayMillisFor(2))
        assertEquals(8_000L, policy.delayMillisFor(3))
    }

    @Test
    fun `超过上限时钳制在最大延迟`() {
        val policy = BackoffPolicy(
            initialDelayMillis = 1_000L,
            maxDelayMillis = 5_000L,
            multiplier = 2.0,
            jitterRatio = 0.0,
        )
        assertEquals(5_000L, policy.delayMillisFor(3))
        assertEquals(5_000L, policy.delayMillisFor(10))
        // 极大 attempt：multiplier^attempt 溢出后仍必须返回上限值
        assertEquals(5_000L, policy.delayMillisFor(1_000))
    }

    @Test
    fun `叠加抖动后依旧不超过上限且非负`() {
        val policy = BackoffPolicy(maxDelayMillis = 5_000L, jitterRatio = 0.2)
        repeat(200) {
            val delay = policy.delayMillisFor(20)
            assertTrue("delay=$delay", delay in 0L..5_000L)
            assertTrue("delay=$delay 应接近上限", delay >= 4_000L)
        }
    }

    @Test
    fun `负数 attempt 按 0 处理`() {
        val policy = BackoffPolicy(jitterRatio = 0.0)
        assertEquals(1_000L, policy.delayMillisFor(-5))
    }

    @Test
    fun `默认参数符合需求规定`() {
        val policy = BackoffPolicy()
        assertEquals(1_000L, policy.initialDelayMillis)
        assertEquals(60_000L, policy.maxDelayMillis)
        assertEquals(2.0, policy.multiplier, 0.0)
        assertEquals(0.2, policy.jitterRatio, 0.0)
    }

    // ---------------- retry ----------------

    @Test
    fun `不可重试错误只调用一次并立即返回失败`() = runTest {
        var calls = 0
        val result = BackoffPolicy().retry(4) { attempt ->
            calls++
            throw MailError.Auth("授权码错误（attempt=$attempt）")
        }

        assertEquals(1, calls)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MailError.Auth)
    }

    @Test
    fun `配置错误同样不重试`() = runTest {
        var calls = 0
        val result = BackoffPolicy().retry(4) {
            calls++
            throw MailError.Config("端口错误")
        }

        assertEquals(1, calls)
        assertTrue(result.exceptionOrNull() is MailError.Config)
    }

    @Test
    fun `可重试错误会重试到次数上限`() = runTest {
        var calls = 0
        val result = BackoffPolicy().retry(3) { attempt ->
            calls++
            assertEquals(calls - 1, attempt)
            throw MailError.Network("网络抖动")
        }

        assertEquals(3, calls)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MailError.Network)
    }

    @Test
    fun `成功时立即返回且只调用一次`() = runTest {
        var calls = 0
        val result = BackoffPolicy().retry(3) { attempt ->
            calls++
            "ok-$attempt"
        }

        assertEquals(1, calls)
        assertEquals("ok-0", result.getOrNull())
    }

    @Test
    fun `中途成功时返回成功结果`() = runTest {
        var calls = 0
        val result = BackoffPolicy().retry(4) { attempt ->
            calls++
            if (attempt < 2) throw MailError.Protocol("服务器响应异常") else "done-$attempt"
        }

        assertEquals(3, calls)
        assertEquals("done-2", result.getOrNull())
    }

    @Test
    fun `未包装的普通异常按可重试处理`() = runTest {
        var calls = 0
        val result = BackoffPolicy().retry(2) {
            calls++
            throw IllegalStateException("unexpected")
        }

        assertEquals(2, calls)
        assertTrue(result.exceptionOrNull() is MailError.Unknown)
    }

    @Test
    fun `取消异常直接向上传播且不重试`() = runTest {
        var calls = 0
        var cancelled = false
        try {
            BackoffPolicy().retry(4) {
                calls++
                throw CancellationException("用户离开页面")
            }
        } catch (c: CancellationException) {
            cancelled = true
            assertEquals("用户离开页面", c.message)
        }

        assertTrue("CancellationException 必须向上传播", cancelled)
        assertEquals(1, calls)
    }
}
