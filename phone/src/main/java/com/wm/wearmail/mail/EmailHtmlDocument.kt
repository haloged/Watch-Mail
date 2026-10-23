package com.wm.wearmail.mail

/**
 * 邮件 HTML 的「可渲染文档」构建器。
 *
 * 为什么需要单独一层（安全 + 适配 + 省电）：
 * 1. **安全**：邮件 HTML 是**不可信内容**。即使 WebView 关闭 JavaScript，
 *    `<meta http-equiv="refresh">` 不需要 JS 也能跳转，`javascript:` 伪协议、
 *    `<iframe>`、`<script>` 都是常见攻击面 —— 这里统一清理后再交给 WebView。
 * 2. **适配**：466px 圆屏上多数邮件按桌面宽度排版，必须注入 viewport 与
 *    「图片/表格不超过容器宽度」的 CSS，否则会横向溢出、被圆弧切掉。
 * 3. **省电与隐私**：默认屏蔽一切远程图片（追踪像素），只有用户显式开启
 *    「显示远程图片」时才在 WebView 层放行网络加载。
 *
 * 纯字符串函数、无 Android 依赖 → 可在纯 JVM 单元测试中完整覆盖。
 */
object EmailHtmlDocument {

    /**
     * 允许保留的 HTML 上限（字符数，与抓取上限一致）。
     *
     * 手表存储与内存都紧张：一封营销邮件的 HTML 常见 200KB~1MB，
     * 超过上限时**不保留 HTML**（只留纯文本），而不是截断成一个残缺文档。
     */
    const val MAX_HTML_CHARS: Int = 200_000

    /** 是否需要且能够渲染 HTML（设置开启 + 确实有 HTML 正文） */
    fun isRenderable(enabled: Boolean, html: String?): Boolean = enabled && !html.isNullOrBlank()

    /** HTML 是否超过保留上限（超过则不缓存，避免把手表存储吃满） */
    fun exceedsLimit(rawHtml: String?): Boolean = (rawHtml?.length ?: 0) > MAX_HTML_CHARS

    // 需要清理的片段：脚本 / 内联框架 / 元刷新（后者无需 JS 即可跳转）
    private val SCRIPT_RE = Regex("<script\\b[^>]*>.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_SELF_CLOSING_RE = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val IFRAME_RE = Regex("<iframe\\b[^>]*>.*?</iframe\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val IFRAME_SELF_CLOSING_RE = Regex("<iframe\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val META_REFRESH_RE = Regex(
        "<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** `javascript:` 伪协议（HTML 中可能写作 `java\nscript:`，故用宽松匹配） */
    private val JS_URL_RE = Regex("javascript\\s*:", RegexOption.IGNORE_CASE)

    /**
     * 构建可渲染文档。
     *
     * @param rawHtml 原始 HTML 正文（来自邮件，不可信）
     * @param allowRemoteImages 是否允许加载远程图片；false 时用 CSS 隐藏 http(s) 图片
     */
    fun build(rawHtml: String, allowRemoteImages: Boolean = false): String {
        val sanitized = sanitize(rawHtml)
        return buildString(sanitized.length + 512) {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">")
            append("<style>")
            append(style(allowRemoteImages))
            append("</style></head><body>")
            append(sanitized)
            append("</body></html>")
        }
    }

    /** 清理不可信片段：脚本、内联框架、元刷新、`javascript:` 伪协议 */
    fun sanitize(rawHtml: String): String {
        var text = rawHtml
        if (text.length > MAX_HTML_CHARS) text = text.take(MAX_HTML_CHARS)
        text = SCRIPT_RE.replace(text, "")
        text = SCRIPT_SELF_CLOSING_RE.replace(text, "")
        text = IFRAME_RE.replace(text, "")
        text = IFRAME_SELF_CLOSING_RE.replace(text, "")
        text = META_REFRESH_RE.replace(text, "")
        text = JS_URL_RE.replace(text, "blocked:")
        return text
    }

    /**
     * 注入样式。
     *
     * 说明：圆屏内容区由 Compose 侧留出安全边距，这里只做「不横向溢出」与
     * 深色主题适配；正文可读性优先（15px / 1.5 行高）。
     */
    private fun style(allowRemoteImages: Boolean): String = buildString {
        append(":root{color-scheme:dark}")
        append("html,body{margin:0;padding:0;background:#000;color:#e8e8e8}")
        append("body{font-family:sans-serif;font-size:15px;line-height:1.5;")
        // 内边距按 466px 圆屏的固定占用给出（单位是 CSS px，WebView 视口 = dp）：
        // 上 78 留出返回按钮与顶部圆弧，下 92 留出底部操作栏，左右 20 避免贴边被圆弧切到。
        // 与纯文本列表的 contentPadding（上 64 / 下 96）保持同一量级。
        append("word-wrap:break-word;overflow-wrap:anywhere;padding:78px 20px 92px}")
        append("img{max-width:100%!important;height:auto!important}")
        append("table{max-width:100%!important;border-collapse:collapse}")
        append("td,th{word-break:break-word}")
        append("a{color:#8ab4f8}")
        append("h1,h2,h3,h4{font-size:17px;margin:8px 0 4px}")
        append("blockquote{margin:6px 0 6px 8px;padding-left:8px;border-left:2px solid #444;color:#bdbdbd}")
        append("pre,code{white-space:pre-wrap;word-break:break-word;font-size:13px}")
        append("hr{border:0;border-top:1px solid #333}")
        if (!allowRemoteImages) {
            // 只隐藏远程图（http/https），保留 data: 内联图；未加载的图片会变成空白框，很难看
            append("img[src^=\"http\"]{display:none}")
        }
    }

    /** 供 UI 展示的说明文案（设置页提示用，避免用户误以为"渲染坏了"） */
    const val HINT_HTML_ENABLED: String =
        "开启后邮件详情可切换 HTML 渲染（更耗内存与存储，仅在性能足够的手表上开启）"

    const val HINT_REMOTE_IMAGES: String =
        "关闭时不会加载远程图片与追踪像素，邮件里的图片会隐藏"
}
