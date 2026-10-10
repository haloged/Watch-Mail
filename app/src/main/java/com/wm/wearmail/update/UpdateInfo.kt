package com.wm.wearmail.update

import org.json.JSONObject

/**
 * 一次「发现新版本」的信息（来自 GitHub Releases API）。
 *
 * 只保留展示与跳转必需的字段；不解析文件名/下载地址 —— 手表端不做应用内
 * 自更新（安装包分发交由 GitHub Releases 页面与用户侧处理）。
 */
data class UpdateInfo(
    /** 原始 tag（如 `v1.2.0`），保留用于排查 */
    val tagName: String,
    /** 去掉 `v` 前缀的版本号（如 `1.2.0`），比较与展示使用 */
    val version: String,
    /** Release 页面地址（在手机/电脑浏览器打开） */
    val releaseUrl: String,
    /** 更新说明（可能为空；展示时截断） */
    val notes: String,
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
        )
    }
}
