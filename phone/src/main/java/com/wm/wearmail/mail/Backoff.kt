package com.wm.wearmail.mail

import com.wm.wearmail.core.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random

private const val TAG = "Backoff"

/**
 * 指数退避策略（协议层与同步层共用的重试工具）。
 *
 * 设计取舍：
 * - 手表端网络质量波动大（蓝牙代理 / 蜂窝切换），固定间隔重试会在弱网下疯狂打点耗电，
 *   因此采用「初始 1 秒、每次翻倍、上限 60 秒」的经典指数退避；
 * - 叠加 ±[jitterRatio] 的随机抖动，避免多账户在同一时刻齐步重连形成「惊群」；
 * - 抖动后仍强制不超过 [maxDelayMillis]，保证最坏情况下重试间隔可控（省电优先）。
 *
 * 注意：本类只负责「算延迟」与「可重试错误的重试循环」，
 * 不感知网络状态；是否值得重试由 [MailError.isRetryable] 决定。
 */
class BackoffPolicy(
    /** 第一次重试的基准延迟（毫秒） */
    val initialDelayMillis: Long = 1_000L,
    /** 延迟上限（毫秒），也是抖动后的硬上限 */
    val maxDelayMillis: Long = 60_000L,
    /** 每轮延迟的放大倍数 */
    val multiplier: Double = 2.0,
    /** 抖动比例：0.2 表示结果落在基准值的 ±20% 区间内 */
    val jitterRatio: Double = 0.2,
) {

    /**
     * 计算第 [attempt] 次重试前应等待的毫秒数（attempt 从 0 开始）。
     *
     * 公式：`initial * multiplier^attempt`，先按 [maxDelayMillis] 封顶，
     * 再叠加 ±[jitterRatio] 的随机抖动，最后再次封顶并保证非负。
     *
     * 边界说明：
     * - `attempt < 0` 按 0 处理；
     * - 抖动在封顶之后再叠加，因此达到上限时结果落在 `[max*(1-jitter), max]`
     *   区间内（上限侧被钳制，这是「不超过 maxDelayMillis」的必然取舍）；
     * - `attempt` 极大时 `multiplier^attempt` 溢出为 Infinity，经封顶后仍返回上限值。
     */
    fun delayMillisFor(attempt: Int): Long {
        val exponent = attempt.coerceAtLeast(0)
        val raw = initialDelayMillis.toDouble() * multiplier.coerceAtLeast(0.0).pow(exponent)
        val capped = raw.coerceIn(0.0, maxDelayMillis.toDouble())
        val jitterSpan = capped * jitterRatio.coerceAtLeast(0.0)
        val jitter = jitterSpan * (Random.nextDouble() * 2.0 - 1.0)
        val safeMax = maxDelayMillis.coerceAtLeast(0L)
        return (capped + jitter).toLong().coerceIn(0L, safeMax)
    }

    /**
     * 按退避策略重试 [block]，最多执行 [maxAttempts] 次（attempt 从 0 到 maxAttempts-1）。
     *
     * 行为约定：
     * - [block] 成功 → 立即返回 `Result.success`，不做多余重试；
     * - 抛出异常且 [Throwable.toMailError] 判定为**不可重试**（认证 / 配置错误）
     *   → 立即返回 `Result.failure`，避免无意义地反复触发登录风控；
     * - 抛出异常且可重试 → 按 [delayMillisFor] 退避后重试，用尽次数返回最后一次失败；
     * - `CancellationException`（协程取消）**必须原样向上抛出**，绝不能被当作失败吞掉，
     *   否则上层协程取消后仍会继续跑（手表端表现为「界面已退出但仍在联网」）。
     *
     * 特别说明：[TimeoutCancellationException] 是我们自己调用 `withTimeout` 触发的超时，
     * 并非外部取消，因此按「可重试的超时错误」处理；而父协程取消抛出的是普通的
     * `CancellationException`，会原样传播。
     */
    suspend fun <T> retry(
        maxAttempts: Int = 4,
        block: suspend (attempt: Int) -> T,
    ): Result<T> {
        val attempts = maxAttempts.coerceAtLeast(1)
        var lastError: MailError? = null

        for (attempt in 0 until attempts) {
            try {
                return Result.success(block(attempt))
            } catch (timeout: TimeoutCancellationException) {
                // 自身超时：可重试
                lastError = timeout.toMailError("操作超时")
                Logs.w(TAG, "第 ${attempt + 1}/$attempts 次尝试超时", timeout)
            } catch (cancel: CancellationException) {
                // 外部取消：立即传播，绝不吞掉
                throw cancel
            } catch (t: Throwable) {
                val error = t.toMailError()
                lastError = error
                if (!error.isRetryable) {
                    Logs.w(TAG, "第 ${attempt + 1}/$attempts 次尝试失败且不可重试，放弃重试", error)
                    return Result.failure(error)
                }
                Logs.w(TAG, "第 ${attempt + 1}/$attempts 次尝试失败（可重试）", error)
            }

            if (attempt < attempts - 1) {
                val delayMillis = delayMillisFor(attempt)
                Logs.d(TAG, "退避 ${delayMillis}ms 后进行第 ${attempt + 2} 次尝试")
                delay(delayMillis)
            }
        }

        return Result.failure(lastError ?: MailError.Unknown("重试失败"))
    }
}
