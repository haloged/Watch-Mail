package com.haloged.watchmail.ui.screen.account

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.haloged.watchmail.data.remote.pairing.PairingAccountPayload
import com.haloged.watchmail.data.remote.pairing.PairingState
import com.haloged.watchmail.data.remote.pairing.QrPairingServer
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil
import com.haloged.watchmail.util.NetworkUtil
import com.haloged.watchmail.util.QrCodeUtil

/**
 * 扫码配对添加账户页
 *
 * 圆形表盘布局：
 *  ┌────────────────────────────┐
 *  │  [←返回]  扫码配置  [48dp] │ ← 顶弧
 *  │        ┌─────────┐         │
 *  │        │ QR 码   │         │
 *  │        └─────────┘         │ ← 中带（核心）
 *  │     配对码 XXXX-XXXX-XXXX  │
 *  │      请用手机浏览器扫码     │
 *  │   [ 等待手机提交 … ]        │ ← 底弧状态
 *  └────────────────────────────┘
 *
 * 生命周期：进入页面启动本地 Web 服务；离开页面 / 收到配置 / 超时 即停止服务。
 */
@Composable
fun PairScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPairSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val context = LocalContext.current

    // 每次进入页面创建一个新的配对会话（配对码每次不同）
    // 同步 remember 创建对象，保证 state.collectAsState() 无条件调用
    var sessionSeed by remember { mutableIntStateOf(0) }
    val server = remember(sessionSeed) { QrPairingServer() }
    var qrBitmap by remember(server) { mutableStateOf<Bitmap?>(null) }
    var localError by remember(sessionSeed) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // 启动本地 Web 服务并生成二维码
    LaunchedEffect(server) {
        saving = false
        localError = null
        qrBitmap = null
        if (server.start()) {
            qrBitmap = QrCodeUtil.generateQrBitmap(server.qrContent, sizePx = 420)
        } else {
            localError = (server.state.value as? PairingState.Error)?.message
                ?: "启动失败：请确认已连接 WiFi"
        }
    }

    // 离开页面时关闭服务（释放端口、停止耗电）
    DisposableEffect(server) {
        onDispose { server.stop() }
    }

    // 监听配对状态（无条件 collect）
    val pairingState by server.state.collectAsState()

    // 收到配置 → 落库 → 返回
    LaunchedEffect(pairingState) {
        when (val st = pairingState) {
            is PairingState.Received -> {
                if (!saving) {
                    saving = true
                    HapticUtil.performConfirmFeedback(view)
                    viewModel.createAccountFromPairing(st.payload) { ok, msg ->
                        if (ok) {
                            server.stop()
                            onPairSuccess()
                        } else {
                            saving = false
                            localError = msg ?: "保存账户失败"
                            HapticUtil.performRejectFeedback(view)
                        }
                    }
                }
            }
            is PairingState.Error -> {
                if (!saving) {
                    HapticUtil.performRejectFeedback(view)
                    localError = st.message
                }
            }
            else -> {}
        }
    }

    val pin = server.pin
    val pinDisplay = remember(pin) { formatPin(pin) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                WatchIconButton(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = {
                        HapticUtil.performLightFeedback(view)
                        server.stop()
                        onBack()
                    },
                    tint = WatchMailColors.TextPrimary
                )
                Text(text = "扫码配置", style = WatchMailTypography.Title)
                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))

            when {
                // ---- 成功 ----
                saving -> {
                    Spacer(modifier = Modifier.height(40.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        indicatorColor = WatchMailColors.Success,
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "正在保存账户…",
                        style = WatchMailTypography.Body,
                        color = WatchMailColors.Success
                    )
                }

                // ---- 错误 ----
                localError != null -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "✗",
                        style = WatchMailTypography.TitleLarge.copy(
                            color = WatchMailColors.Error,
                            fontSize = 40.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = localError!!,
                        style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Error),
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // 重试：递增 seed 重新创建会话（新配对码）
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(WatchMailColors.Primary)
                            .clickable {
                                HapticUtil.performLightFeedback(view)
                                sessionSeed += 1
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "重新开始",
                            style = WatchMailTypography.Button
                        )
                    }
                }

                // ---- 等待扫码（主状态） ----
                else -> {
                    // 二维码
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "扫码配置二维码",
                            modifier = Modifier
                                .size(168.dp) // 圆形安全区内可完整显示
                                .clip(RoundedCornerShape(8.dp))
                                .background(androidx.compose.ui.graphics.Color.White)
                                .padding(4.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(168.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(WatchMailColors.Surface),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                indicatorColor = WatchMailColors.Primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 配对码（手动输入备用）
                    Text(
                        text = "配对码",
                        style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextSecondary)
                    )
                    Text(
                        text = pinDisplay,
                        style = WatchMailTypography.Title.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = WatchMailColors.Primary,
                            fontSize = 18.sp
                        ),
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "同一 WiFi 下用手机扫码",
                        style = WatchMailTypography.Caption,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 状态提示
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(WatchMailColors.Warning)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "等待手机提交… 5 分钟内有效",
                            style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextSecondary),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 配对码分组显示：ABCDEFGHJKMN → ABCD-EFGH-JKMN（4 位一组，便于肉眼核对）
 */
private fun formatPin(pin: String): String {
    if (pin.isEmpty()) return "----"
    return pin.chunked(4).joinToString("-")
}
