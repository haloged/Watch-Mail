package com.wm.wearmail.update

import com.wm.wearmail.core.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一条下载进度（节流后回调：约每 200ms 一次） */
data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** 是否走的 gh-proxy.com 加速线路 */
    val viaProxy: Boolean,
) {
    /** 0..100；服务端未提供总大小时返回 -1（UI 显示不确定进度） */
    val percent: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
}

/** 一次下载的结局 */
sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult

    /** [message] 为面向用户的简短文案；技术细节只进日志 */
    data class Failed(val message: String) : DownloadResult
}

/**
 * APK 下载器。
 *
 * 行为约定（由 [HttpApkDownloader] 实现）：
 * - 先直连下载；**连接失败、IO 失败或速度过慢**时自动切换 gh-proxy.com
 *   加速链接重试一次（候选序列见 [DownloadUrls.candidates]）；
 * - [onProgress] 在 IO 线程被调用（约每 200ms 一次），**调用方自行切回主线程**；
 * - 协程取消时中止并清理半成品文件。
 */
interface ApkDownloader {
    suspend fun download(
        url: String,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult
}

/**
 * 下载地址策略（纯函数，可单元测试）。
 *
 * 加速源固定为 `https://gh-proxy.com/`（前缀式代理，拼在完整原始 URL 前）。
 * 只对 GitHub 系域名做加速：其它域名（含加速源本身）不重复拼前缀。
 */
object DownloadUrls {

    /** 加速前缀（需求指定） */
    const val PROXY_PREFIX = "https://gh-proxy.com/"

    /** 是否属于可加速的 GitHub 链接（asset 会 302 到 objects.githubusercontent.com） */
    fun isAccelerable(url: String): Boolean =
        url.startsWith("https://github.com/") ||
            url.startsWith("https://objects.githubusercontent.com/") ||
            url.startsWith("https://release-assets.githubusercontent.com/")

    /**
     * 候选下载地址：第一个直连；若可加速则追加代理链接作为第二个候选。
     */
    fun candidates(url: String): List<String> =
        if (isAccelerable(url)) listOf(url, PROXY_PREFIX + url) else listOf(url)
}

/**
 * 下载行为参数与速度判定（纯函数，可单元测试）。
 *
 * 「过慢」的定义：观察窗口（默认 6 秒）内平均速度低于
 * [MIN_SPEED_BYTES_PER_SEC]（24 KB/s）—— 手表上一枚 3.5MB 的包
 * 若低于此速需要 2 分钟以上，不如直接走加速源。
 */
object DownloadPolicy {

    /** 观察窗口：每满一个窗口评估一次速度 */
    const val OBSERVE_WINDOW_MS = 6_000L

    /** 窗口内平均速度低于该值视为「过慢」，切换加速源 */
    const val MIN_SPEED_BYTES_PER_SEC = 24_000L

    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 12_000

    /** 进度回调节流间隔（避免每秒几十次重组） */
    const val PROGRESS_THROTTLE_MS = 200L

    const val USER_AGENT = "WearMail-Updater"

    /**
     * 当前窗口内速度是否过低。
     *
     * 用「窗口字节数 × 1000 < 最低速度 × 窗口毫秒数」的整数比较表达，
     * 窗口未满时不判定（连接建立与首包延迟不计入）。
     */
    fun isTooSlow(bytesInWindow: Long, windowMs: Long): Boolean =
        windowMs >= OBSERVE_WINDOW_MS &&
            bytesInWindow * 1000L < MIN_SPEED_BYTES_PER_SEC * windowMs
}

/**
 * [ApkDownloader] 的 HttpURLConnection 实现（零第三方依赖，与检查更新同款技术选型）。
 *
 * 实现要点：
 * - 每个候选地址下载到 `*.part` 半成品，成功后原子替换目标文件——中断/失败
 *   不会留下一个"看起来完整"的坏包被安装；
 * - 速度评估用滑动 checkpoint（每 6 秒一段），避免"先快后慢"被全局平均掩盖；
 * - 响应未提供 Content-Length（`-1`）时跳过完整性校验，进度按不确定态回调。
 */
class HttpApkDownloader : ApkDownloader {

    override suspend fun download(
        url: String,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val candidates = DownloadUrls.candidates(url)
        for ((index, candidate) in candidates.withIndex()) {
            val viaProxy = index > 0
            val ok = try {
                downloadFrom(candidate, target, viaProxy, onProgress)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Logs.w(TAG, "下载失败（proxy=$viaProxy）：${t.javaClass.simpleName}")
                false
            }
            if (ok) return@withContext DownloadResult.Success(target)
            // 失败/过慢 → 继续下一个候选（通常是 gh-proxy 加速源）
        }
        DownloadResult.Failed("下载失败，请检查网络连接后重试")
    }

    /** 从单个地址下载完整文件；返回是否成功。半成品与连接在内部清理。 */
    private suspend fun downloadFrom(
        url: String,
        target: File,
        viaProxy: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DownloadPolicy.CONNECT_TIMEOUT_MS
            readTimeout = DownloadPolicy.READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DownloadPolicy.USER_AGENT)
        }
        val part = File(target.absolutePath + PART_SUFFIX)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Logs.w(TAG, "下载响应异常 HTTP $code（proxy=$viaProxy）")
                return false
            }
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()

            val buffer = ByteArray(BUFFER_BYTES)
            var downloaded = 0L
            var lastNotifyNs = 0L
            var checkpointAtNs = System.nanoTime()
            var checkpointBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val nowNs = System.nanoTime()
                        val windowMs = (nowNs - checkpointAtNs) / 1_000_000
                        if (DownloadPolicy.isTooSlow(downloaded - checkpointBytes, windowMs)) {
                            Logs.i(TAG, "下载速度过慢（proxy=$viaProxy），切换重试")
                            return false
                        }
                        if (windowMs >= DownloadPolicy.OBSERVE_WINDOW_MS) {
                            checkpointAtNs = nowNs
                            checkpointBytes = downloaded
                        }
                        if ((nowNs - lastNotifyNs) / 1_000_000 >= DownloadPolicy.PROGRESS_THROTTLE_MS) {
                            lastNotifyNs = nowNs
                            onProgress(DownloadProgress(downloaded, total, viaProxy))
                        }
                    }
                    output.flush()
                }
            }

            // 服务端给了总大小时校验完整性（断流会在这里被拦下）
            if (total > 0 && downloaded != total) {
                Logs.w(TAG, "下载不完整：$downloaded/$total（proxy=$viaProxy）")
                return false
            }
            target.delete()
            if (!part.renameTo(target)) {
                // 极罕见（跨分区等）：回退为复制
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(DownloadProgress(downloaded, if (total > 0) total else downloaded, viaProxy))
            Logs.i(TAG, "下载完成 ${downloaded}B（proxy=$viaProxy）")
            return true
        } catch (t: CancellationException) {
            runCatching { part.delete() }
            throw t
        } catch (t: IOException) {
            Logs.w(TAG, "下载 IO 失败（proxy=$viaProxy）：${t.javaClass.simpleName}")
            return false
        } finally {
            runCatching { part.delete() } // 成功路径上 part 已被 rename，delete 无副作用
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        private const val TAG = "ApkDownloader"
        private const val BUFFER_BYTES = 16 * 1024
        private const val PART_SUFFIX = ".part"
    }
}
