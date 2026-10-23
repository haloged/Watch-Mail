package com.wm.wearmail.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.wm.wearmail.pairing.PairingService

/**
 * 「同步到手机」面板：显示一次性会话二维码，等手机配套应用扫码拉取全部账户配置。
 *
 * 背景：OPPO 等手表系统限制了手表端第三方应用的通知，但会同步手机通知。
 * 手机配套应用收信后发出手机通知，通知即可经手表同步到达。
 *
 * 交互：进入即启动局域网服务（未连接 Wi-Fi 时直接显示错误）；手机扫码后
 * 服务端返回配置并回填 `exportedCount`，这里切到「已同步 N 个账户」；
 * 关闭（或点外部）时立刻停止服务，避免端口长期暴露。
 */
@Composable
fun SyncToPhoneDialog(
    pairing: PairingService,
    onDismiss: () -> Unit,
) {
    val state by pairing.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (!pairing.state.value.running) pairing.start()
    }

    fun close() {
        pairing.stop()
        onDismiss()
    }

    AlertDialog(
        visible = true,
        onDismissRequest = { close() },
        title = { Text(text = "同步到手机", maxLines = 1) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val qr = state.qrBitmap
                when {
                    qr != null -> Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "同步二维码",
                        modifier = Modifier.size(112.dp),
                    )

                    state.error != null -> Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    else -> Text(
                        text = "正在启动…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val synced = state.exportedCount > 0
                Text(
                    text = if (synced) {
                        "已同步 ${state.exportedCount} 个账户 ✓\n可以关闭本页面了"
                    } else {
                        "用 WearMail 手机应用扫描二维码，自动同步全部账户配置"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (synced) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { close() }) { Text(text = "关闭") }
        },
    )
}
