package com.wm.wearmail.update

/**
 * 语义化版本号的解析与比较（纯函数，无 Android 依赖，可单元测试）。
 *
 * 之所以不直接比较字符串：`"1.10.0" < "1.9.0"` 按字典序成立但语义上错误，
 * 会导致「有新版本却不提示」。这里按数字段逐段比较。
 *
 * 容错策略（面向 GitHub Release 的实际格式）：
 * - 允许 `v` / `V` 前缀（如 `v1.2.0`）；
 * - 忽略预发布/构建元数据后缀（`1.2.0-beta.1` → `[1, 2, 0]`）：手表端
 *   只做「有没有更新」的判断，不值得为预发布标签做更细的区分；
 * - 任何一侧解析失败时**不提示更新**（宁可不提醒，也不误报）。
 */
object AppVersion {

    /**
     * 解析版本字符串为数字段列表（如 `"v1.2.0"` → `[1, 2, 0]`）；
     * 无法解析时返回 null（空串、非数字段等）。
     */
    fun parse(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        // 去掉预发布（-beta.1）与构建元数据（+20240101）后缀，只留核心版本
        val core = cleaned.substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        }
    }

    /**
     * [candidate] 是否比 [current] 更新（严格大于）。
     *
     * 缺位按 0 补齐：`1.0` 与 `1.0.0` 视为相同版本。
     * 任一侧解析失败时返回 false —— 解析失败绝不能导致误报「有新版本」。
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
