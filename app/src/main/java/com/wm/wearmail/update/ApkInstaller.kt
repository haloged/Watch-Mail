package com.wm.wearmail.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.wm.wearmail.core.Logs
import java.io.File

/** 发起系统安装器的结果（弹窗据此决定后续文案） */
sealed interface InstallLaunch {
    /** 安装器已拉起，系统接管（应用内更新流程到此结束） */
    data object Started : InstallLaunch

    /** 缺少「安装未知应用」授权：已跳转系统设置页，用户授权后回来重试 */
    data object NeedsPermission : InstallLaunch

    /** 没有可用的安装器 / URI 生成失败（极罕见） */
    data object Unavailable : InstallLaunch
}

/**
 * 用系统安装器安装下载好的 APK。
 *
 * 两个平台约束（Android 8.0+，本项目 minSdk 30）：
 * 1. 安装 APK 需要 `REQUEST_INSTALL_PACKAGES` 权限 + 用户对「安装未知应用」
 *    的显式授权 —— 未授权时跳系统设置页，而不是静默失败；
 * 2. 安装器**不接受 `file://`**（StrictMode 拦截）—— APK 经
 *    [FileProvider] 以 `content://` 暴露，仅授予安装器一次性读权限。
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun launch(context: Context, apk: File): InstallLaunch {
        if (!apk.exists()) {
            Logs.w(TAG, "安装包不存在")
            return InstallLaunch.Unavailable
        }

        // 1) 未授权「安装未知应用」：先跳系统设置页（授权后用户可再次点「安装」）
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val jumped = runCatching { context.startActivity(settings) }.isSuccess
            Logs.i(TAG, "缺少安装授权，跳转设置页：$jumped")
            return if (jumped) InstallLaunch.NeedsPermission else InstallLaunch.Unavailable
        }

        // 2) 生成 content:// URI 并拉起安装器
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrElse { t ->
            Logs.w(TAG, "生成安装 URI 失败：${t.javaClass.simpleName}")
            return InstallLaunch.Unavailable
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { context.startActivity(intent) }.isSuccess
        if (!started) Logs.w(TAG, "拉起系统安装器失败")
        return if (started) InstallLaunch.Started else InstallLaunch.Unavailable
    }
}
