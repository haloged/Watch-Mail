package com.wm.wearmail.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wm.wearmail.model.AuthType
import com.wm.wearmail.phone.data.PairingUrl
import com.wm.wearmail.phone.data.PhoneStore
import com.wm.wearmail.phone.net.ConfigSync
import com.wm.wearmail.phone.notify.MailNotifier
import com.wm.wearmail.phone.service.MailSyncService
import kotlinx.coroutines.launch

/**
 * 腕邮手机（WearMail 手机配套应用）单页。
 *
 * 定位（务必保持）：
 * - **只做两件事**：扫码同步手表配置、收信后发手机通知；
 * - **不提供任何查看邮件的界面**（没有邮件列表/详情）；
 * - 手机通知会被 OPPO 等手表自动同步 —— 这是绕开手表端通知限制的路径；
 * - 首次启动弹出**使用说明**（OPPO 健康通知权限 + 后台保活）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearMailPhoneTheme {
                PhoneApp()
            }
        }
    }
}

@Composable
private fun WearMailPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
private fun PhoneApp() {
    val context = LocalContext.current
    val store = remember { PhoneStore(context) }
    val scope = rememberCoroutineScope()
    val notifier = remember { MailNotifier(context) }

    var accounts by remember { mutableStateOf(store.loadAccounts()) }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var notificationsEnabled by remember { mutableStateOf(store.notificationsEnabled) }
    var syncInterval by remember { mutableStateOf(store.syncIntervalMinutes) }
    // 首次启动弹出使用说明（OPPO 健康通知权限 + 后台保活）
    var showGuide by remember { mutableStateOf(!store.guideShown) }

    // Android 13+ 通知运行时权限：未授权时通知会静默失败，因此进页即请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) message = "未授予通知权限：新邮件将不会提醒（可在系统设置中开启）"
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 已有配置则确保收信服务在运行（服务是 START_STICKY 的，重复 start 无副作用）
    LaunchedEffect(accounts.size) {
        if (accounts.isNotEmpty()) MailSyncService.start(context)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) return@rememberLauncherForActivityResult
        val endpoint = PairingUrl.parse(raw)
        if (endpoint == null) {
            message = "二维码无法识别（请扫描手表「设置 → 手机云通知」里的二维码）"
            return@rememberLauncherForActivityResult
        }
        syncing = true
        message = null
        scope.launch {
            val fetched = ConfigSync.fetchConfig(endpoint)
            syncing = false
            fetched.fold(
                onSuccess = { json ->
                    val parsed = store.saveConfig(json)
                    accounts = parsed
                    message = when {
                        parsed.isEmpty() -> "已连接，但没有可同步的账户（请在手表上确认账户配置）"
                        else -> "已同步 ${parsed.size} 个账户，手机将开始收信并发出通知"
                    }
                    if (parsed.isNotEmpty()) MailSyncService.start(context)
                },
                onFailure = {
                    message = "同步失败：请确认手机与手表在同一 Wi-Fi，" +
                        "且手表上的「连接手机云通知」面板仍开着（关闭后链接即失效）"
                },
            )
        }
    }

    // ---------------- 使用说明（首次启动） ----------------
    if (showGuide) {
        UsageGuideDialog(
            onDismiss = {
                store.guideShown = true
                showGuide = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("腕邮手机", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "扫码同步手表上的邮箱配置，由手机收信并发出手机通知（手表会自动同步手机通知）。" +
                "本应用只负责同步与提醒，不提供查看邮件的功能。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("扫描手表上的同步二维码")
                        setBeepEnabled(false)
                        // 自定义竖屏扫码页（库默认「锁定当前方向」会导致横屏）
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                    },
                )
            },
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (syncing) "正在同步…" else "扫码同步手表配置")
        }

        message?.let { text ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("已同步账户（${accounts.size}）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for (account in accounts) {
                val authLabel = if (account.authType == AuthType.OAUTH2) "OAuth2" else "密码/授权码"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(account.email, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${account.imapHost}:${account.imapPort} · $authLabel · 凭据已加密保存在本机",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("新邮件通知", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "关闭后仍会收信，但不再发出提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { enabled ->
                        notificationsEnabled = enabled
                        store.notificationsEnabled = enabled
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("自动同步间隔", style = MaterialTheme.typography.titleSmall)
            Text(
                "间隔越短越及时，也更耗电（默认 1 分钟）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (minutes in PhoneStore.INTERVAL_OPTIONS) {
                    FilterChip(
                        selected = syncInterval == minutes,
                        onClick = {
                            syncInterval = minutes
                            store.syncIntervalMinutes = minutes
                            message = "同步间隔已设为 $minutes 分钟（下一轮生效）"
                        },
                        label = { Text("$minutes 分钟") },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    notifier.ensureChannels()
                    notifier.notifyTest()
                    message = "已发送测试通知；若手机/手表都没看到，请检查通知权限与后台保活"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("发送测试通知")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    MailSyncService.stop(context)
                    store.clear()
                    accounts = emptyList()
                    message = "已停止同步并清除本机配置"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止同步并清除配置")
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "工作原理：手表「设置 → 手机云通知 → 连接手机云通知」会显示二维码；本应用扫码后" +
                "拉取全部账户配置，手机自行收信并发出手机通知。OPPO 等手表会自动同步手机通知，" +
                "因此通知能到达手表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 首次启动的使用说明：OPPO 健康通知权限 + 后台保活。
 *
 * 这两步是「通知到不了手表」的最常见原因（真机反馈），
 * 必须在用户第一次使用时就讲清楚，而不是等出问题再猜。
 */
@Composable
private fun UsageGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用说明") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                GuideSection(
                    title = "① 在 OPPO 健康中开启通知权限",
                    body = "OPPO 手表只显示手机通知。请在手机「OPPO 健康」App → 设备 → " +
                        "腕邮手机（WearMail）→ 通知权限 中允许通知，否则手表收不到任何提醒。",
                )
                GuideSection(
                    title = "② 后台保活（重要）",
                    body = "避免通知被系统拦截：\n" +
                        "· 系统设置 → 应用 → 腕邮手机 → 耗电管理：允许后台活动（不优化）\n" +
                        "· 允许「自启动」\n" +
                        "· 在最近任务界面锁定本应用（下拉或长按加锁），防止被清理",
                )
                GuideSection(
                    title = "③ 连接手表",
                    body = "手机与手表连同一 Wi-Fi，在手表「设置 → 手机云通知 → 连接手机云通知」" +
                        "显示二维码，用本应用扫码同步账户。",
                )
                Text(
                    "本应用只负责同步与通知，不提供查看邮件的功能。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
    )
}

@Composable
private fun GuideSection(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(2.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
}
