package com.wm.wearmail.update

import org.json.JSONObject

/**
 * 一次「发现新版本」的信息（来自 GitHub Releases API）。
 *
 * 含应用内更新所需的 [apkUrl]（Release assets 里的安装包）与 [notes]
 * （Release 说明，即「更新日志」）。
 */
data class UpdateInfo(
    /** 原始 tag（如 `v1.2.0`），保留用于排查 */
    val tagName: String,
    /** 去掉 `v` 前缀的版本号（如 `1.2.0`），比较与展示使用 */
    val version: String,
    /** Release 页面地址（assets 缺失时降级用） */
    val releaseUrl: String,
    /** 更新说明（Release body，展示前经 [ReleaseNotes.toDisplayText] 清洗） */
    val notes: String,
    /**
     * 应用内下载地址（assets 里的 APK `browser_download_url`）；
     * 找不到可用的 APK 资产时为 null，弹窗降级为「打开下载页」。
     */
    val apkUrl: String? = null,
)

/**
 * GitHub Releases API 响应的**纯解析逻辑**（无网络，可单元测试）。
 *
 * 兼容 `GET /repos/{owner}/{repo}/releases/latest` 的响应体；
 * 字段缺失或 JSON 非法时返回 null（调用方按「没有可用更新信息」处理）。
 */
object UpdateJson {

    /**
     * 解析 `releases/latest` 响应。
     *
     * @return 解析成功返回 [UpdateInfo]；`tag_name` / `html_url` 缺失或
     *         JSON 非法时返回 null。
     */
    fun parseLatestRelease(json: String): UpdateInfo? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = obj.optString("tag_name").trim()
        val url = obj.optString("html_url").trim()
        if (tag.isEmpty() || url.isEmpty()) return null
        return UpdateInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            releaseUrl = url,
            notes = obj.optString("body").trim(),
            apkUrl = pickApkUrl(obj),
        )
    }

    /**
     * 从 `assets` 里挑一个 APK 下载地址。
     *
     * 规则（按顺序）：
     * 1. 只接受 `.apk` 结尾且为 https 的附件；
     * 2. **排除手机配套应用的安装包**（文件名含 `phone`）—— 同一个 Release 里常同时上传
     *    手表包与手机包，手表端若下到手机包会安装失败；
     * 3. 优先文件名含 `release` 的（正式包，避免误取 debug 包），否则取第一个 .apk；
     * 4. 多个候选时按 GitHub 返回的 assets 顺序取第一个。
     *
     * 找不到时返回 null（弹窗降级为「打开下载页」）。
     */
    private fun pickApkUrl(obj: JSONObject): String? {
        val assets = obj.optJSONArray("assets") ?: return null
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (!url.startsWith("https://")) continue
            // 手机配套应用的 APK 不能装到手表上（两个 release 包同时上传时尤为重要）
            if (name.contains(PHONE_APK_KEYWORD, ignoreCase = true)) continue
            if (name.contains("release", ignoreCase = true)) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    /** 手机配套应用安装包的文件名关键字（`wearmail-phone-release.apk` 等） */
    private const val PHONE_APK_KEYWORD = "phone"
}

/**
 * Release 说明（Markdown 源文）→ 手表弹窗可读的纯文本（纯函数，可单元测试）。
 *
 * 为什么要清洗：GitHub Release 的 body 通常是 Markdown（`### 标题`、`**加粗**`、
 * `- 列表`），手表弹窗空间小、也无力渲染 Markdown —— 直接原样显示会出现
 * 一堆符号噪音。这里做**轻度**清洗（去标记、列表符号换 `·`、折叠空行、限长），
 * 不做完整 Markdown 渲染。
 */
object ReleaseNotes {

    /** 默认展示上限（字符）：超出截断，避免超长日志占满手表内存 */
    const val DEFAULT_MAX_CHARS = 2_000

    private val HEADING_RE = Regex("^\\s*#{1,6}\\s*")
    private val LIST_RE = Regex("^\\s*[-*+]\\s+")
    private val BLANK_LINES_RE = Regex("\n{3,}")

    fun toDisplayText(raw: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (raw.isBlank()) return ""
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val cleaned = normalized.lineSequence()
            .joinToString("\n") { line ->
                var l = line.trimEnd()
                l = l.replace(HEADING_RE, "")              // 去标题前缀 ###
                l = l.replace("**", "").replace("__", "")  // 去粗体标记
                l = l.replace(LIST_RE, "· ")               // 列表项 → ·
                l
            }
            .replace(BLANK_LINES_RE, "\n\n")
            .trim()
        return if (cleaned.length > maxChars) cleaned.take(maxChars).trimEnd() + "…" else cleaned
    }
}
