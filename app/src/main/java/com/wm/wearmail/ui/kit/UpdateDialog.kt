package com.wm.wearmail.ui.kit

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.wm.wearmail.update.ApkDownloader
import com.wm.wearmail.update.ApkInstaller
import com.wm.wearmail.update.DownloadResult
import com.wm.wearmail.update.InstallLaunch
import com.wm.wearmail.update.ReleaseNotes
import com.wm.wearmail.update.UpdateInfo
import java.io.File
import kotlinx.coroutines.launch

/** 弹窗的本地阶段（下载只在弹窗存活期内进行，关闭弹窗即取消） */
private sealed interface UpdatePhase {
    /** 初始：展示更新日志 + 「立即下载」 */
    data object Ready : UpdatePhase

    /** 下载中：percent 为 -1 表示总大小未知（不确定进度） */
    data class Downloading(val percent: Int, val viaProxy: Boolean) : UpdatePhase

    /** 需要用户注意的终态提示（安装授权 / 无浏览器指引等），按钮「知道了」 */
    data class Notice(val text: String) : UpdatePhase

    /** 下载失败（可重试） */
    data class Failed(val message: String) : UpdatePhase
}

/**
 * 「发现新版本」提示弹窗（启动自动检查与设置页手动检查共用）。
 *
 * 交互设计（v1.2.2 起）：
 * - **展示更新日志**：Release body 经 [ReleaseNotes] 轻度清洗后放入限高滚动区；
 * - **应用内直接下载**：不再跳 Release 网页 —— 「立即下载」在应用内下载 APK
 *   （直连慢/失败时自动切换 gh-proxy 加速），进度与线路实时展示；
 * - 下载完成后**自动拉起系统安装器**（需要「安装未知应用」授权时跳系统设置页并提示）；
 * - 弹窗关闭即取消下载（半成品会清理）；失败可原地重试；
 * - 资产缺失（apkUrl=null）时降级为「打开下载页」，再失败则给出手动指引。
 *
 * @param info 最新版本信息（来自 [com.wm.wearmail.update.UpdateCenter]）
 * @param currentVersion 当前版本（展示对比用）
 * @param downloader 应用内下载器（容器注入，便于测试替身）
 * @param onDismiss 关闭弹窗（调用方负责清状态，见 UpdateCenter.dismissUpdate）
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    currentVersion: String,
    downloader: ApkDownloader,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 按版本号重置：换版本重新弹窗时回到初始态
    var phase by remember(info.version) { mutableStateOf<UpdatePhase>(UpdatePhase.Ready) }
    val notes = remember(info.notes) { ReleaseNotes.toDisplayText(info.notes) }
    val apkUrl = info.apkUrl

    /** 打开 Release 网页（降级路径）；失败给出可执行的手动指引 */
    fun openReleasePage() {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (opened) {
            onDismiss()
        } else {
            phase = UpdatePhase.Notice(
                "手表上没有可用浏览器。请在手机或电脑打开 github.com/haloged/Watch-Mail 下载新版。",
            )
        }
    }

    /** 应用内下载 → 自动拉起安装器 */
    fun startDownload() {
        val url = apkUrl
        if (url == null) {
            openReleasePage()
            return
        }
        phase = UpdatePhase.Downloading(percent = -1, viaProxy = false)
        scope.launch {
            val target = File(context.filesDir, "updates/wearmail-${info.version}.apk")
            val result = downloader.download(url, target) { progress ->
                // 回调在 IO 线程：转发到主线程更新弹窗（已离开下载态就忽略，避免竞态覆盖）
                scope.launch {
                    if (phase is UpdatePhase.Downloading) {
                        phase = UpdatePhase.Downloading(progress.percent, progress.viaProxy)
                    }
                }
            }
            phase = when (result) {
                is DownloadResult.Success -> when (ApkInstaller.launch(context, result.file)) {
                    InstallLaunch.Started -> {
                        // 安装器已接管：关闭弹窗；失败重进时仍可从设置页重新检查
                        onDismiss()
                        return@launch
                    }
                    InstallLaunch.NeedsPermission ->
                        UpdatePhase.Notice("请在系统设置中允许「安装未知应用」，然后重新下载安装。")
                    InstallLaunch.Unavailable ->
                        UpdatePhase.Notice("未找到可用的安装器，无法完成安装。")
                }
                is DownloadResult.Failed -> UpdatePhase.Failed(result.message)
            }
        }
    }

    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发现新版本 v${info.version}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前版本 v$currentVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "更新日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = notes.ifEmpty { "（该版本未附带更新说明）" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 84.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // 下载状态区（按阶段显示）
                when (val current = phase) {
                    is UpdatePhase.Downloading -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = buildString {
                                append(
                                    if (current.percent >= 0) {
                                        "正在下载 ${current.percent}%"
                                    } else {
                                        "正在下载…"
                                    },
                                )
                                if (current.viaProxy) append("（加速线路）")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 自绘进度条（避免依赖进度组件的 API 差异；仿 MiniOptionButton 的自绘风格）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            if (current.percent >= 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(current.percent / 100f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }

                    is UpdatePhase.Failed -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    is UpdatePhase.Notice -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = current.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    UpdatePhase.Ready -> Unit
                }
            }
        },
        confirmButton = {
            when (phase) {
                UpdatePhase.Ready -> TextButton(onClick = {
                    if (apkUrl != null) startDownload() else openReleasePage()
                }) {
                    Text(text = if (apkUrl != null) "立即下载" else "打开下载页")
                }

                is UpdatePhase.Downloading -> TextButton(onClick = onDismiss) {
                    Text(text = "取消")
                }

                is UpdatePhase.Failed -> TextButton(onClick = { startDownload() }) {
                    Text(text = "重试")
                }

                is UpdatePhase.Notice -> TextButton(onClick = onDismiss) {
                    Text(text = "知道了")
                }
            }
        },
        // 下载中只显示「取消」（dismissButton 槽保持恒定 lambda，内部条件渲染 ——
        // 直接在此处写 `if (…) null else {…}` 会让类型推断退化为 Nothing?，
        // 进而拖垮 AlertDialog 的重载解析）
        dismissButton = {
            if (phase !is UpdatePhase.Downloading) {
                TextButton(onClick = onDismiss) { Text(text = "稍后") }
            }
        },
    )
}
