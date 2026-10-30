package com.wm.wearmail.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 日志落盘（按天一个文件，供「设置 → 反馈中心 → 上传日志」）。
 *
 * 设计取舍：
 * - **不阻塞业务线程**：写日志只是 `offer` 进有界队列（满了直接丢弃），
 *   由后台线程批量追加并 flush —— 腕表 IO 慢，绝不能因为写日志拖慢同步/UI；
 * - **按天分文件**：`filesDir/logs/wearmail-yyyy-MM-dd.log`，跨天自动切换；
 * - **容量自保**：单文件超过 [MAX_FILE_BYTES] 后停止写入（保留已有内容，避免撑爆存储）；
 * - **只保留最近 [KEEP_DAYS] 天**：初始化时清理旧文件；
 * - 失败一律静默（日志系统本身不能影响应用运行）。
 */
object LogFileWriter {

    /** 日志目录名（相对 filesDir） */
    const val DIR_NAME = "logs"

    /** 单日日志上限：512 KB（远大于正常一天的量，异常刷屏时也不会撑爆存储） */
    const val MAX_FILE_BYTES: Long = 512L * 1024L

    /** 保留天数 */
    const val KEEP_DAYS: Int = 3

    private const val QUEUE_CAPACITY = 1024
    private const val BATCH_MAX_LINES = 64
    private const val FLUSH_INTERVAL_MS = 1_000L

    @Volatile
    private var logsDir: File? = null

    @Volatile
    private var worker: Thread? = null

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    /** 当天文件与已写字节数（只在后台线程读写） */
    private var currentFileName: String = ""
    private var currentBytes: Long = 0L
    private var limitWarned = false

    /** 应用启动时调用一次；重复调用无副作用 */
    fun init(context: Context) {
        if (logsDir != null) return
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        runCatching { dir.mkdirs() }
        logsDir = dir
        runCatching { cleanup(dir) }
        startWorker()
    }

    /** 写入一行日志（队列满时丢弃，绝不阻塞调用方） */
    fun write(level: String, tag: String, message: String) {
        if (logsDir == null) return
        queue.offer(LogText.oneLine(level, tag, message))
    }

    /**
     * 当天日志文件（可能不存在——表示今天还没有日志写入）。
     *
     * 供「上传日志」读取；不存在或为空时调用方应提示「先使用一会儿再上传」。
     */
    fun todayFile(): File? = logsDir?.let { File(it, LogText.fileNameFor(System.currentTimeMillis())) }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun startWorker() {
        if (worker?.isAlive == true) return
        val thread = Thread {
            while (true) {
                try {
                    val first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    val batch = ArrayList<String>(BATCH_MAX_LINES)
                    if (first != null) batch.add(first)
                    while (batch.size < BATCH_MAX_LINES) {
                        val next = queue.poll() ?: break
                        batch.add(next)
                    }
                    if (batch.isNotEmpty()) appendBatch(batch)
                } catch (t: Throwable) {
                    // 日志系统自身异常必须吞掉：不能因为写日志失败影响应用
                }
            }
        }
        thread.isDaemon = true
        thread.name = "WearMail-LogFile"
        worker = thread
        thread.start()
    }

    private fun appendBatch(lines: List<String>) {
        val dir = logsDir ?: return
        val name = LogText.fileNameFor(System.currentTimeMillis())
        if (name != currentFileName) {
            // 跨天（或首次写入）：切换文件并重置计数
            currentFileName = name
            currentBytes = runCatching { File(dir, name).length() }.getOrDefault(0L)
            limitWarned = false
        }
        if (currentBytes >= MAX_FILE_BYTES) {
            if (!limitWarned) {
                limitWarned = true
                runCatching {
                    File(dir, name).appendText(
                        LogText.oneLine("W", "LogFileWriter", "日志已达 ${MAX_FILE_BYTES / 1024} KB 上限，后续内容不再写入"),
                    )
                }
            }
            return
        }
        val payload = buildString {
            for (line in lines) append(line).append('\n')
        }
        runCatching {
            File(dir, name).appendText(payload)
            currentBytes += payload.toByteArray(Charsets.UTF_8).size
        }
    }

    /** 清理超过保留天数的日志文件 */
    private fun cleanup(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(FILE_PREFIX) && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private const val FILE_PREFIX = "wearmail-"
}

/**
 * 日志文本的格式化与转义（纯函数，可单元测试）。
 *
 * 单独抽出来的原因：日志内容会拼进多行文本文件，**换行必须转义**，
 * 否则一条异常堆栈会把「一行一日志」的结构打乱，也让上传后的可读性变差。
 */
object LogText {

    private const val MAX_MESSAGE_CHARS = 1_000

    /**
     * 生成一行日志：`HH:mm:ss.SSS [L] Tag: message`。
     *
     * @param now 时间源（测试注入）
     * @param timeZone 时区（测试注入，默认系统时区）
     */
    fun oneLine(
        level: String,
        tag: String,
        message: String,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { this.timeZone = timeZone }.format(Date(now))
        return "$time [$level] $tag: ${sanitize(message)}"
    }

    /** 把换行/回车压平为 `⏎`，并限制单条长度（避免一条日志占满文件） */
    fun sanitize(message: String, maxChars: Int = MAX_MESSAGE_CHARS): String {
        val flat = message.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎').replace('\t', ' ')
        return if (flat.length <= maxChars) flat else flat.take(maxChars) + "…"
    }

    /** 当天日志文件名：`wearmail-yyyy-MM-dd.log` */
    fun fileNameFor(dayMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }.format(Date(dayMillis))
        return "wearmail-$day.log"
    }
}
