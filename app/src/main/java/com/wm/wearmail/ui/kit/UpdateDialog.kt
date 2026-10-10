package com.wm.wearmail.ui.kit

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wm.wearmail.update.UpdateInfo

/**
 * 「发现新版本」提示弹窗（启动自动检查与设置页手动检查共用）。
 *
 * 交互设计：
 * - 「前往下载」：尝试用系统浏览器打开 Release 页面；**手表上通常没有浏览器**，
 *   打开失败时**不关闭弹窗**，改为展示手动访问指引（GitHub 地址），
 *   并把按钮切换为「知道了」——用户不必凭记忆去别处找下载入口；
 * - 「稍后」：关闭本次提示（下次启动仍会提示；设置页可随时手动再查）。
 *
 * 说明：不展示 Release 说明正文（notes）—— 手表弹窗空间有限，
 * 版本文案越短越容易一眼看清。
 *
 * @param info 最新版本信息（来自 [com.wm.wearmail.update.UpdateCenter]）
 * @param currentVersion 当前版本（展示对比用）
 * @param onDismiss 关闭弹窗（调用方负责清状态，见 UpdateCenter.dismissUpdate）
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 打开浏览器的降级状态；按版本号重置（换版本重新弹窗时回到初始态）
    var openFailed by remember(info.version) { mutableStateOf(false) }

    ConfirmDialog(
        visible = true,
        title = "发现新版本 v${info.version}",
        message = if (openFailed) {
            "手表上没有可用浏览器。请在手机或电脑打开 github.com/haloged/Watch-Mail 下载新版。"
        } else {
            "当前版本 v$currentVersion。更新可获取最新的修复与改进，是否前往下载？"
        },
        confirmText = if (openFailed) "知道了" else "前往下载",
        dismissText = "稍后",
        onConfirm = {
            if (openFailed) {
                onDismiss()
            } else {
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                // 打开成功即关闭弹窗；失败则原地降级为手动指引
                if (opened) onDismiss() else openFailed = true
            }
        },
        onDismiss = onDismiss,
    )
}
