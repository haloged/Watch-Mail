package com.wm.wearmail.mail

/**
 * HTML → 纯文本转换（**纯 Kotlin 实现，禁止依赖 android.text.***）。
 *
 * 为什么自己写而不用 `Html.fromHtml`：
 * 1. `android.text.Html` 属于 Android 框架，无法在纯 JVM 单元测试中运行，
 *    而「HTML 邮件正文降级为纯文本」是列表与详情页的核心逻辑，必须可测；
 * 2. 手表端 466×466 圆形表盘只能展示纯文本，我们不需要保留任何富文本样式，
 *    只需要「结构化的换行 + 干净的文本」。
 *
 * 处理顺序（顺序本身很关键）：
 * 1. 去掉注释、`<script>`、`<style>`、`<!DOCTYPE>` 等非正文内容；
 * 2. 把块级标签转换为换行（先转换再删标签，否则会丢掉段落结构）；
 * 3. 删除其余标签；
 * 4. **最后**解码实体（若先解码，`&lt;div&gt;` 会被当成真标签误删）；
 * 5. 归一化空白、压缩连续空行、trim。
 */
object HtmlTextExtractor {

    /** `<!-- ... -->` 注释，含条件注释 */
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /** `<script>...</script>`：脚本内容绝不能出现在正文里 */
    private val SCRIPT = Regex(
        "<script\\b[^>]*>.*?</script\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<style>...</style>`：CSS 同样不是正文 */
    private val STYLE = Regex(
        "<style\\b[^>]*>.*?</style\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<!DOCTYPE html>`、`<![CDATA[...]]>` 之类的声明 */
    private val DECLARATION = Regex("<!\\[?[^>]*>", RegexOption.DOT_MATCHES_ALL)

    /** `<br>` / `<br/>` / `<br />` → 换行 */
    private val LINE_BREAK = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** 段落与表格整体 → 空行（压缩后最多保留一个空行） */
    private val BLOCK_BREAK = Regex(
        "</?(?:p|table|tbody|thead|tfoot)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** 其它块级标签 → 换行 */
    private val BLOCK_TAG = Regex(
        "</?(?:div|tr|li|ul|ol|dl|dt|dd|h[1-6]|blockquote|section|article|" +
            "header|footer|aside|nav|pre|hr|form|fieldset)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** 表格单元格 → 空格（保持同一行内的可读性） */
    private val CELL_TAG = Regex("</?(?:td|th)\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 剩余标签一律删除。
     *
     * 要求 `<` 后紧跟字母（可带 `/`）才算标签，这样纯文本里的
     * 「3 < 5 且 7 > 2」不会被误删（浏览器同样要求标签名以字母开头）。
     */
    private val ANY_TAG = Regex("</?[a-zA-Z][^>]*>")

    /** 行内水平空白（含 `&nbsp;` 解码后的 U+00A0） */
    private val HORIZONTAL_SPACE = Regex("[\\t\\u000B\\u000C\\r \\u00A0\\u2007\\u202F]+")

    /** 实体：`&amp;`、`&#39;`、`&#x1F600;` */
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});")

    /** 常见命名实体；不认识的实体保持原样，避免误伤正文 */
    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "nbsp" to "\u00A0",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "hellip" to "\u2026",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "middot" to "\u00B7",
        "times" to "\u00D7",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "deg" to "\u00B0",
        "sect" to "\u00A7",
        "yen" to "\u00A5",
        "euro" to "\u20AC",
        "pound" to "\u00A3",
    )

    /**
     * 把 [html] 转换为手表端可直接展示的纯文本。
     *
     * @return 已 trim 的文本；空串 / 纯空白 / 无有效内容时返回空串。
     */
    fun toPlainText(html: String): String {
        if (html.isEmpty()) return ""

        var text = html
        text = COMMENT.replace(text, "")
        text = SCRIPT.replace(text, "\n")
        text = STYLE.replace(text, "\n")
        text = DECLARATION.replace(text, "")
        text = LINE_BREAK.replace(text, "\n")
        text = BLOCK_BREAK.replace(text, "\n\n")
        text = BLOCK_TAG.replace(text, "\n")
        text = CELL_TAG.replace(text, " ")
        text = ANY_TAG.replace(text, "")
        text = decodeEntities(text)
        text = HORIZONTAL_SPACE.replace(text, " ")

        return compressBlankLines(text)
    }

    /** 解码 HTML 实体（命名 + 十进制 + 十六进制数字实体） */
    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            val decoded = when {
                body.startsWith("#x", ignoreCase = true) ->
                    codePointToString(body.substring(2), radix = 16) ?: match.value

                body.startsWith("#") ->
                    codePointToString(body.substring(1), radix = 10) ?: match.value

                else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
            }
            decoded
        }
    }

    /** 数字实体 → 字符；非法码位返回 null（保持原文，不丢信息） */
    private fun codePointToString(digits: String, radix: Int): String? {
        if (digits.isEmpty()) return null
        val codePoint = digits.toIntOrNull(radix) ?: return null
        if (!Character.isValidCodePoint(codePoint)) return null
        // 代理区（U+D800..U+DFFF）不是合法字符，toChars 会产出非法字符串
        if (codePoint in 0xD800..0xDFFF) return null
        return String(Character.toChars(codePoint))
    }

    /**
     * 逐行 trim 并压缩空行：
     * - 行内空白已在前面归一化为单个空格；
     * - **连续空行最多保留一个**（邮件里常见的 `<p>&nbsp;</p>` 堆叠会产出大量空行）；
     * - 首尾空行直接丢弃。
     */
    private fun compressBlankLines(text: String): String {
        val builder = StringBuilder(text.length)
        var pendingBlankLine = false

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                // 只有已经写出内容后才需要记录空行，避免开头出现空行
                if (builder.isNotEmpty()) pendingBlankLine = true
                continue
            }
            if (pendingBlankLine) {
                builder.append("\n\n")
                pendingBlankLine = false
            } else if (builder.isNotEmpty()) {
                builder.append('\n')
            }
            builder.append(line)
        }

        return builder.toString()
    }
}
