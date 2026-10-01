package com.wm.wearmail.ui.screen.inbox

import com.wm.wearmail.model.Account
import com.wm.wearmail.model.MailAddress

/**
 * 收件箱列表行的纯文本格式化（无 Android / Compose 依赖，可单元测试）。
 *
 * 为什么把这类逻辑抽成纯函数：
 * - 466px 圆形表盘上「一行能放几个字」是硬约束，截断规则必须可回归验证；
 * - 中文、emoji 与西文的宽度感知截断很容易写错（`String.length` 不等于字符数），
 *   把规则集中在 [truncate] 里，测试覆盖一次即可。
 *
 * 全部方法只做「展示文本」决策，不做任何排版（字号/颜色由 UI 决定）。
 */
object InboxRowFormatter {

    /** 省略号（单码点，占用 1 个字符预算） */
    const val ELLIPSIS: String = "…"

    /** 无主题占位文案，与 [com.wm.wearmail.model.EmailMeta.subjectOrPlaceholder] 保持一致 */
    const val NO_SUBJECT: String = "(无主题)"

    /**
     * 发件人展示标签：显示名优先，其次邮箱 `@` 之前的部分。
     *
     * 例：
     * - `MailAddress("a@b.com", "张三")` → `张三`
     * - `MailAddress("zhangsan@b.com")` → `zhangsan`
     * - 超长显示名 → 截断 + `…`
     *
     * @param maxChars 最大显示字符数（按 Unicode 码点计，emoji 记 1）
     */
    fun senderLabel(from: MailAddress, maxChars: Int = 14): String {
        val displayName = from.name?.trim().orEmpty()
        val raw = if (displayName.isNotEmpty()) {
            displayName
        } else {
            // 没有显示名时退化为邮箱 @ 前部分，避免整行被域名占满
            from.address.substringBefore('@').trim()
        }
        if (raw.isNotEmpty()) return truncate(raw, maxChars)

        // 兜底：(未知发件人) 这类占位文本
        return truncate(from.address.trim(), maxChars)
    }

    /**
     * 主题单行文本：空主题 → [NO_SUBJECT]；压缩所有空白（含换行/Tab）；超长截断加省略号。
     *
     * 注意：这里不负责 `maxLines = 1`，UI 仍应显式设置
     * `maxLines = 1, overflow = TextOverflow.Ellipsis` 作为二次保护。
     */
    fun subjectLine(subject: String, maxChars: Int = 30): String {
        val normalized = subject.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return NO_SUBJECT
        return truncate(normalized, maxChars)
    }

    /**
     * 账户筛选项文本：别名优先，否则邮箱 `@` 前部分；最长 [MAX_FILTER_CHARS] 字符。
     *
     * 筛选项是横向滚动的按钮，标签过长会把「全部」挤出可视区，
     * 因此这里用比列表更严格的 8 字符上限。
     */
    fun accountFilterLabel(account: Account): String {
        val raw = account.displayLabel.trim()
        if (raw.isNotEmpty()) return truncate(raw, MAX_FILTER_CHARS)
        return truncate(account.email.trim(), MAX_FILTER_CHARS)
    }

    /**
     * 未读角标文本：
     * - `0` → 空串（不显示角标）
     * - 负数按 0 处理
     * - `1..99` → 数字
     * - `> 99` → `99+`（表盘空间有限，不显示精确大数）
     */
    fun unreadBadgeText(count: Int): String = when {
        count <= 0 -> ""
        count > MAX_BADGE_COUNT -> "$MAX_BADGE_COUNT+"
        else -> count.toString()
    }

    /** 未读角标显示精确数字的上限，超过即显示 `99+` */
    const val MAX_BADGE_COUNT: Int = 99

    /** 账户筛选标签最大字符数 */
    const val MAX_FILTER_CHARS: Int = 8

    /**
     * 按 Unicode 码点截断：超出 [maxChars] 时保留前 `maxChars - 1` 个字符并追加 [ELLIPSIS]。
     *
     * 之所以按码点而不是 `String.length`（UTF-16 码元）：
     * emoji（如 🙂）在 UTF-16 中占 2 个码元，用 `length` 截断会把代理对劈开，
     * 渲染出「半个字符」或空白方块。
     */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        // 一般情况（纯 BMP 文本）无需分配码点数组，先走快路径
        if (text.length <= maxChars) return text
        val codePoints = text.codePoints().toArray()
        if (codePoints.size <= maxChars) return text
        return buildString(maxChars + 1) {
            appendCodePoints(codePoints, maxChars - 1)
            append(ELLIPSIS)
        }
    }

    /** 追加码点数组的前 [count] 个码点（保持代理对完整） */
    private fun StringBuilder.appendCodePoints(codePoints: IntArray, count: Int) {
        var index = 0
        while (index < count && index < codePoints.size) {
            appendCodePoint(codePoints[index])
            index++
        }
    }
}
