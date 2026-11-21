package com.wm.wearmail.core

import android.content.Context
import com.wm.wearmail.model.Account
import com.wm.wearmail.model.AccountSecrets
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 导入结果摘要（用于界面提示） */
data class ImportSummary(
    val accountsAdded: Int,
    val accountsUpdated: Int,
    val settingsApplied: Boolean,
)

/**
 * 一键导入配置（**开发测试功能**，**双击**「关于」里的腕邮文字触发）。
 *
 * 数据来源：[ConfigExporter] 导出的 JSON（位于**文档目录** `Documents/exports/`，取最新一份）。
 *
 * 行为：
 * - **账户**：按邮箱匹配 —— 已存在则更新服务器参数（**保留原凭据**，`secrets = null`）；
 *   不存在则新增（**不含密码**，需要用户重新填写/授权）；
 * - **设置项**：能迁移的字段整体覆盖（同步频率、通知、缓存上限、HTML/图片/链接/垃圾邮件开关等）；
 * - 文件里**本来就没有凭据**（导出时已剥离），所以导入过程不会写入任何密码。
 */
object ConfigImporter {

    const val FILE_PREFIX = "wearmail-config-"

    /** 文档目录 exports 下最新的配置文件；没有则返回 null */
    fun latestFile(context: Context): File? {
        val dir = ConfigExporter.exportDir(context) ?: return null
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(".json")
        }?.maxByOrNull { it.lastModified() }
    }

    /** 解析后的配置（纯数据，便于单元测试） */
    data class Parsed(
        val accounts: List<Account>,
        val settings: SettingsSnapshot?,
    )

    /** 设置快照（只包含可迁移字段，null 表示文件里没有对应键） */
    data class SettingsSnapshot(
        val foregroundSyncMinutes: Int?,
        val backgroundSyncMinutes: Int?,
        val notificationsEnabled: Boolean?,
        val wifiOnlySync: Boolean?,
        val maxCachedHeaders: Int?,
        val maxCachedBodies: Int?,
        val htmlRenderingEnabled: Boolean?,
        val remoteImagesEnabled: Boolean?,
        val openLinksInBrowser: Boolean?,
        val junkMailMediaEnabled: Boolean?,
        val microsoftOAuthTenant: String?,
    )

    /**
     * 解析导出 JSON（纯函数）。
     *
     * - 账户：只要 `email` 非空就接受；服务器参数缺失时用常见默认值兜底；
     * - 设置：逐个字段判断是否存在，**缺省字段不覆盖**用户的现值。
     */
    fun parse(json: String): Parsed {
        val root = JSONObject(json)
        val accounts = mutableListOf<Account>()
        val array: JSONArray? = root.optJSONArray("accounts")
        if (array != null) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val email = o.optString("email").trim()
                if (email.isBlank()) continue
                accounts += Account(
                    email = email,
                    alias = o.optString("alias"),
                    authType = enumOrDefault(o.optString("authType"), AuthType.APP_PASSWORD),
                    imapHost = o.optString("imapHost"),
                    imapPort = o.optInt("imapPort", 993),
                    imapSecurity = enumOrDefault(o.optString("imapSecurity"), MailSecurity.SSL_TLS),
                    smtpHost = o.optString("smtpHost"),
                    smtpPort = o.optInt("smtpPort", 465),
                    smtpSecurity = enumOrDefault(o.optString("smtpSecurity"), MailSecurity.SSL_TLS),
                )
            }
        }
        val s = root.optJSONObject("settings")
        val snapshot = s?.let {
            SettingsSnapshot(
                foregroundSyncMinutes = it.takeIf { o -> o.has("foregroundSyncMinutes") }?.optInt("foregroundSyncMinutes"),
                backgroundSyncMinutes = it.takeIf { o -> o.has("backgroundSyncMinutes") }?.optInt("backgroundSyncMinutes"),
                notificationsEnabled = it.takeIf { o -> o.has("notificationsEnabled") }?.optBoolean("notificationsEnabled"),
                wifiOnlySync = it.takeIf { o -> o.has("wifiOnlySync") }?.optBoolean("wifiOnlySync"),
                maxCachedHeaders = it.takeIf { o -> o.has("maxCachedHeaders") }?.optInt("maxCachedHeaders"),
                maxCachedBodies = it.takeIf { o -> o.has("maxCachedBodies") }?.optInt("maxCachedBodies"),
                htmlRenderingEnabled = it.takeIf { o -> o.has("htmlRenderingEnabled") }?.optBoolean("htmlRenderingEnabled"),
                remoteImagesEnabled = it.takeIf { o -> o.has("remoteImagesEnabled") }?.optBoolean("remoteImagesEnabled"),
                openLinksInBrowser = it.takeIf { o -> o.has("openLinksInBrowser") }?.optBoolean("openLinksInBrowser"),
                junkMailMediaEnabled = it.takeIf { o -> o.has("junkMailMediaEnabled") }?.optBoolean("junkMailMediaEnabled"),
                microsoftOAuthTenant = it.takeIf { o -> o.has("microsoftOAuthTenant") }?.optString("microsoftOAuthTenant"),
            )
        }
        return Parsed(accounts = accounts, settings = snapshot)
    }

    /** 读取最新导出文件并应用（账户按邮箱合并，设置整体覆盖可迁移字段） */
    suspend fun import(context: Context, container: AppContainer): Result<ImportSummary> = runCatching {
        val file = latestFile(context) ?: throw IllegalStateException("文档目录中没有找到配置文件")
        val parsed = parse(file.readText(Charsets.UTF_8))

        val existing = container.accounts.load().associateBy { it.email.lowercase() }
        var added = 0
        var updated = 0
        for (incoming in parsed.accounts) {
            val current = existing[incoming.email.lowercase()]
            if (current == null) {
                container.accounts.add(incoming, AccountSecrets())
                added++
            } else {
                // 已存在：只更新服务器参数与别名，凭据传 null 表示保留原密文
                container.accounts.update(
                    current.copy(
                        alias = incoming.alias,
                        authType = incoming.authType,
                        imapHost = incoming.imapHost,
                        imapPort = incoming.imapPort,
                        imapSecurity = incoming.imapSecurity,
                        smtpHost = incoming.smtpHost,
                        smtpPort = incoming.smtpPort,
                        smtpSecurity = incoming.smtpSecurity,
                    ),
                    secrets = null,
                )
                updated++
            }
        }

        val snapshot = parsed.settings
        if (snapshot != null) {
            container.settings.update { current ->
                current.copy(
                    foregroundSyncMinutes = snapshot.foregroundSyncMinutes ?: current.foregroundSyncMinutes,
                    backgroundSyncMinutes = snapshot.backgroundSyncMinutes ?: current.backgroundSyncMinutes,
                    notificationsEnabled = snapshot.notificationsEnabled ?: current.notificationsEnabled,
                    wifiOnlySync = snapshot.wifiOnlySync ?: current.wifiOnlySync,
                    maxCachedHeaders = snapshot.maxCachedHeaders ?: current.maxCachedHeaders,
                    maxCachedBodies = snapshot.maxCachedBodies ?: current.maxCachedBodies,
                    htmlRenderingEnabled = snapshot.htmlRenderingEnabled ?: current.htmlRenderingEnabled,
                    remoteImagesEnabled = snapshot.remoteImagesEnabled ?: current.remoteImagesEnabled,
                    openLinksInBrowser = snapshot.openLinksInBrowser ?: current.openLinksInBrowser,
                    junkMailMediaEnabled = snapshot.junkMailMediaEnabled ?: current.junkMailMediaEnabled,
                    microsoftOAuthTenant = snapshot.microsoftOAuthTenant ?: current.microsoftOAuthTenant,
                )
            }
        }
        Logs.i(TAG, "配置已导入：${file.name}（新增 $added，更新 $updated）")
        ImportSummary(accountsAdded = added, accountsUpdated = updated, settingsApplied = snapshot != null)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    private const val TAG = "ConfigImporter"
}
