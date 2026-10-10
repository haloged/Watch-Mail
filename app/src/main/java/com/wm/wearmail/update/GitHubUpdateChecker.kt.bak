package com.wm.wearmail.update

import com.wm.wearmail.core.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [UpdateChecker] 的 GitHub Releases 实现
 * （`GET /repos/{owner}/{repo}/releases/latest`）。
 *
 * 实现注意：
 * - **GitHub API 要求 User-Agent**，缺失会直接返回 403 —— 真机上只会表现为
 *   「检查更新失败」，很难排查，因此这里显式设置常量值；
 * - 不引入 OkHttp/Gson：项目保持零第三方网络库，`HttpURLConnection` + org.json
 *   足够覆盖「读一个 JSON、取三个字段」的需求，也避免为一个小功能增大包体；
 * - 只读 `tag_name` / `html_url` / `body` 三个字段，不解析下载资产列表
 *   （手表端不做应用内自更新，安装包分发交由 Release 页面处理）。
 */
class GitHubUpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
) : UpdateChecker {

    override suspend fun fetchLatest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                // 解析失败（字段缺失/非法 JSON）与网络失败同路返回
                UpdateJson.parseLatestRelease(body) ?: throw IOException("响应无法解析")
            } finally {
                runCatching { connection.disconnect() }
            }
        }.onFailure { t ->
            // 只记异常类型：消息里可能包含 URL 等细节，日志保持简短
            Logs.w(TAG, "查询最新 Release 失败：${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "UpdateCheck"

        /** 仓库归属：https://github.com/haloged/Watch-Mail */
        const val DEFAULT_OWNER = "haloged"
        const val DEFAULT_REPO = "Watch-Mail"

        private const val USER_AGENT = "WearMail-UpdateChecker"
        private const val TIMEOUT_MS = 8_000
    }
}
