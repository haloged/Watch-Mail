package com.wm.wearmail.core

import android.content.Context
import android.os.Environment
import com.wm.wearmail.data.prefs.AppSettings
import com.wm.wearmail.model.Account
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一键导出当前配置（**开发测试功能**）。
 *
 * 入口：设置 →「关于」→ 长按「腕邮 WearMail」。
 *
 * 安全约束（务必保持）：
 * - **绝不导出凭据**：密码/授权码/OAuth 令牌一律不写入；
 * - OAuth 客户端 ID 只导出「是否已配置」；签名只导出长度；
 * - 文件写入应用私有目录 `filesDir/exports/`，用 adb `run-as` 或上传日志时一并提供。
 *
 * 产物：**文档目录** `exports/wearmail-config-yyyyMMdd-HHmmss.json`（账户服务器参数 + 开关项，便于复现问题）。
 * 导入入口见 [ConfigImporter]（设置 → 关于 → **双击**腕邮文字）。
 */
object ConfigExporter {

    const val DIR_NAME: String = "exports"

    /** 生成导出 JSON（纯函数，可单元测试） */
    fun buildJson(
        accounts: List<Account>,
        settings: AppSettings,
        exportedAtMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(exportedAtMillis))

        val accountArray = JSONArray()
        for (account in accounts) {
            accountArray.put(
                JSONObject().apply {
                    put("email", account.email)
                    put("alias", account.alias)
                    put("authType", account.authType.name)
                    put("imapHost", account.imapHost)
                    put("imapPort", account.imapPort)
                    put("imapSecurity", account.imapSecurity.name)
                    put("smtpHost", account.smtpHost)
                    put("smtpPort", account.smtpPort)
                    put("smtpSecurity", account.smtpSecurity.name)
                },
            )
        }

        val settingsObject = JSONObject().apply {
            put("foregroundSyncMinutes", settings.foregroundSyncMinutes)
            put("backgroundSyncMinutes", settings.backgroundSyncMinutes)
            put("notificationsEnabled", settings.notificationsEnabled)
            put("wifiOnlySync", settings.wifiOnlySync)
            put("maxCachedHeaders", settings.maxCachedHeaders)
            put("maxCachedBodies", settings.maxCachedBodies)
            put("htmlRenderingEnabled", settings.htmlRenderingEnabled)
            put("remoteImagesEnabled", settings.remoteImagesEnabled)
            put("openLinksInBrowser", settings.openLinksInBrowser)
            // 签名可能包含个人信息：只记录长度，不导出内容
            put("signatureLength", settings.signature.length)
            put("microsoftOAuthTenant", settings.microsoftOAuthTenant)
            // 客户端 ID 本身不导出，只记录是否配置
            put("microsoftOAuthClientIdConfigured", settings.microsoftOAuthClientId.isNotBlank())
            put("junkMailMediaEnabled", settings.junkMailMediaEnabled)
        }

        return JSONObject().apply {
            put("format", "wearmail-config")
            put("formatVersion", 1)
            put("exportedAt", stamp)
            put("accounts", accountArray)
            put("settings", settingsObject)
        }.toString(2)
    }

    /**
     * 导出目录：**文档目录**（`getExternalFilesDir(DIRECTORY_DOCUMENTS)/exports`）。
     *
     * 放在应用专属的外部文档目录：**无需存储权限**，用文件管理器/MTP 可直接看到并备份；
     * 极少数没有外部存储的设备回退到内部 `filesDir/exports`。
     */
    fun exportDir(context: Context): File? {
        val base = context.applicationContext
            .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.applicationContext.filesDir
        val dir = File(base, DIR_NAME)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    /** 写入文档目录下的 `exports/`，返回导出的文件 */
    fun export(
        context: Context,
        accounts: List<Account>,
        settings: AppSettings,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<File> = runCatching {
        val dir = exportDir(context) ?: throw IllegalStateException("无法创建文档目录")
        dir.mkdirs()
        val name = "wearmail-config-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis)) + ".json"
        val file = File(dir, name)
        file.writeText(buildJson(accounts, settings, nowMillis), Charsets.UTF_8)
        Logs.i(TAG, "配置已导出：${file.name}（${file.length()} 字节）")
        file
    }

    private const val TAG = "ConfigExporter"
}
