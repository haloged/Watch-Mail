package com.wm.wearmail.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.wm.wearmail.pairing.QrCodeRenderer

/**
 * 「下载手机版」二维码弹窗。
 *
 * 二维码内容为 GitHub Releases 页面：手机扫码后用浏览器打开下载页，
 * 安装「WearMail 同步」（手机配套应用）。
 *
 * 为什么不在手表上下载：手表没有浏览器，也没有合适的下载/安装链路；
 * 二维码是把手机引到下载页的最短路径。
 */
@Composable
fun PhoneAppDownloadDialog(onDismiss: () -> Unit) {
    val bitmap = remember { QrCodeRenderer.render(RELEASES_URL, QR_SIZE_PX) }

    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = "下载手机版", maxLines = 1) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val qr = bitmap
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "下载二维码",
                        modifier = Modifier.size(112.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "用手机扫描二维码打开 GitHub Releases 下载页，" +
                        "安装「WearMail 同步」（手机配套应用）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        },
    )
}

/** 下载页地址（与检查更新使用同一个仓库） */
private const val RELEASES_URL = "https://github.com/haloged/Watch-Mail/releases"

/** 二维码像素尺寸（与配对二维码一致） */
private const val QR_SIZE_PX = 300
