package com.wm.wearmail.ui.kit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import com.wm.wearmail.ui.theme.accountColor

/**
 * 图标集中定义。
 *
 * 只使用 `material-icons-core` 中的图标集（约 45 个）：
 * `material-icons-extended` 会带来上千个矢量资源、显著增大手表 APK 体积，
 * 因此这里统一收口，UI 各页面一律通过 [WearIcons] 取图标，
 * 避免出现「图标名写错」或「误引入 extended 依赖」两类问题。
 */
object WearIcons {
    val Mail: ImageVector = Icons.Filled.Email
    val MailOutline: ImageVector = Icons.Filled.MailOutline

    /** 回复/撰写（core 集无 Reply 图标，用铅笔代替） */
    val Reply: ImageVector = Icons.Filled.Create
    val Edit: ImageVector = Icons.Filled.Edit
    val Delete: ImageVector = Icons.Filled.Delete
    val Refresh: ImageVector = Icons.Filled.Refresh
    val Send: ImageVector = Icons.Filled.Send
    val Settings: ImageVector = Icons.Filled.Settings
    val Add: ImageVector = Icons.Filled.Add
    val Back: ImageVector = Icons.Filled.ArrowBack
    val Close: ImageVector = Icons.Filled.Close
    val Check: ImageVector = Icons.Filled.Check
    val Done: ImageVector = Icons.Filled.Done
    val Warning: ImageVector = Icons.Filled.Warning
    val Info: ImageVector = Icons.Filled.Info
    val Person: ImageVector = Icons.Filled.Person

    /** 手机（用于「用手机扫码配置」入口，core 图标集无二维码图标） */
    val Phone: ImageVector = Icons.Filled.Phone
    val Account: ImageVector = Icons.Filled.AccountCircle
    val Lock: ImageVector = Icons.Filled.Lock
    val More: ImageVector = Icons.Filled.MoreVert
    val Star: ImageVector = Icons.Filled.Star
    val Search: ImageVector = Icons.Filled.Search
    val List: ImageVector = Icons.Filled.List
    val Notifications: ImageVector = Icons.Filled.Notifications
}

/**
 * 页面根容器。
 *
 * 布局约定（466x466 圆形表盘）：
 * - 顶部弧形区：状态栏（[TimeText] 显示时间）；
 * - 中部圆形安全区：页面主体；
 * - 底部：由各页面自行放置主操作按钮（如「新建邮件」）。
 *
 * [TimeText] 使用铺满整屏的尺寸：Wear 的弧形文字布局需要以整个圆形表盘为
 * 参照系计算圆弧半径，若限制成小矩形会导致弧线错位。它只绘制顶部弧线、
 * 不消费触摸事件，因此不会遮挡主体内容的点击；主体内容请通过列表的
 * `contentPadding`（默认顶部 52dp）避开弧线区域。
 */
@Composable
fun CircularScreen(
    modifier: Modifier = Modifier,
    showTimeText: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // 根节点必须自己绘制不透明背景。
            // 分页页面背后是 Activity 的黑色窗口，所以"不画背景"看不出问题；
            // 但详情/撰写/添加账户是画在分页**之上**的覆盖层，根节点一旦透明，
            // 空白处就会直接透出下面的收件箱（曾经的缺陷）。
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (showTimeText) {
            TimeText(modifier = Modifier.fillMaxSize())
        }
        content()
    }
}

/** 账户标识圆点（列表项中标识邮件来源账户） */
@Composable
fun AccountDot(
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 9.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(accountColor(colorIndex)),
    )
}

/** 账户首字母徽标（筛选项/账户列表中使用） */
@Composable
fun AccountInitialBadge(
    label: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(accountColor(colorIndex)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.trim().take(1).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
        )
    }
}

/** 未读标记圆点 */
@Composable
fun UnreadDot(
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** 分段小标题（列表分组用） */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 空态：图标 + 标题 + 提示 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 错误提示条。
 *
 * 设计取舍：圆形表盘不适合 Toast/Snackbar 弹出层（易被裁切），
 * 因此错误以「列表首项」的形式内联展示，并提供一键重试。
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Card(
        onClick = { onRetry?.invoke() },
        modifier = modifier.fillMaxWidth(),
        enabled = onRetry != null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = WearIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 加载指示（发送中/同步中） */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = 2.dp,
    )
}

/** 确认对话框（删除邮件、删除账户等破坏性操作） */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    confirmText: String = "确定",
    dismissText: String = "取消",
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { if (message != null) Text(text = message, style = MaterialTheme.typography.labelMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = dismissText) }
        },
    )
}

/**
 * 主操作按钮。
 *
 * @param compact true = **内容宽度**的紧凑胶囊。推荐用于底部弧形区：
 *   圆形表盘越靠下可用弦长越小（y=207dp 处约 106dp），整行 52dp 大按钮放到低位
 *   会被圆弧切掉两端，因此越是贴近底部的主操作越应采用紧凑胶囊。
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    if (compact) {
        CompactButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            label = {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 迷你胶囊的视觉高度：**24dp ≈ 52dp 按钮的一半**。
 *
 * 为什么不再往下调：本机密度 ≈2.0（466px / 233dp），24dp 正好是 **48px**，
 * 恰好满足需求「触控热区不小于 48×48px」。再小就不合规了。
 *
 * 为什么不用 [CompactButton]：它内部固定 48dp 高（`CompactButtonDefaults.Height`），
 * 从外部传 `Modifier.height` 只会裁切内容，因此这两个迷你件直接用
 * clip + background + clickable 自绘。
 */
private val MINI_BUTTON_HEIGHT = 24.dp

/**
 * 迷你主操作按钮：视觉高度约为 [PrimaryActionButton] 的一半。
 *
 * 圆屏纵向预算只有 233dp，底部弧形区每多占 1dp，中间的信息流就少 1dp：
 * 原先「新建邮件」用 CompactButton（48dp）+ 上下留白，与顶部筛选条一起吃掉 168dp，
 * 干净可视带只剩 65dp（约 1.5 行邮件）。减半后信息流接近翻倍。
 *
 * @param icon 前置图标（按 12dp 绘制，与迷你高度匹配）
 */
@Composable
fun MiniActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .height(MINI_BUTTON_HEIGHT)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 迷你选项按钮（筛选项专用）：视觉高度约为 [CompactOptionButton] 的一半。
 *
 * 注意：`leading` / `trailing` 是外部传入的 composable，**不会**自动继承这里的
 * 选中态文字颜色 —— 调用方需要自己按 [selected] 着色（见 InboxScreen 的 FilterChip）。
 */
@Composable
fun MiniOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .height(MINI_BUTTON_HEIGHT)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(3.dp))
            trailing()
        }
    }
}

/**
 * 紧凑选项按钮（内容宽度，非整行）。
 *
 * 为什么不用 [Button]：Wear Material3 的 `Button` 容器高 52dp 且**占满整行**。
 * 单选组（服务商预设 / 加密方式 / 同步频率 / 发件账户）若每个选项都用它，
 * 在 466px 圆屏上会变成一摞巨型胶囊 —— 既浪费纵向空间（AddAccount 一页曾有 14 条），
 * 也让表盘显得笨重。
 *
 * [CompactButton] 只包住内容宽度、标签用 LabelSmall，配合 [CompactOptionGroup]
 * 自适应换行后，同样 5 个选项只占 2 行；其自带的 tap target padding 仍保证
 * 触控热区不小于 48dp（满足需求 1.2）。
 *
 * @param leading 选项前缀内容（如账户色点、图标）
 * @param trailing 选项后缀内容（如未读数）
 */
@Composable
fun CompactOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showSelectedCheck: Boolean = true,
) {
    CompactButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        label = {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) {
                Spacer(modifier = Modifier.width(4.dp))
                trailing()
            }
            if (selected && showSelectedCheck) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = WearIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        },
    )
}

/**
 * 自适应换行的紧凑选项组。
 *
 * 把一组选项放进**同一个列表项**，每行放得下几个就放几个：
 * 相比「一个选项一条整行大按钮」的排法，纵向占用通常可减少 60% 以上。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactOptionGroup(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 6.dp,
    verticalSpacing: Dp = 6.dp,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/** 纯文本选项组（最常见场景的便捷封装） */
@Composable
fun CompactOptionGroupOf(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompactOptionGroup(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            CompactOptionButton(
                text = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * 次要操作按钮：紧凑胶囊 + 内容宽度。
 *
 * 用于「自动探测」「验证连接」「用手机扫码配置」这类次要动作，
 * 避免与页面唯一的主操作（[PrimaryActionButton]）争抢视觉重心。
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    CompactButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        label = {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

/**
 * 手表端单行/多行文本输入框。
 *
 * 重要设计约束：`androidx.wear.compose:compose-material3` **没有** TextField 组件
 * （Wear 的输入惯例是全屏编辑器），因此这里基于 Compose 基础的
 * [BasicTextField] 自建一个符合表盘尺寸的输入框：
 * - 圆角容器 + 明确的聚焦描边，保证在 466px 圆屏上可辨识；
 * - 支持 Android 系统键盘与语音输入（Wear OS 3+ 由系统弹出）；
 * - 通过 [keyboardType] 切换邮箱/数字键盘，减少输入成本。
 */
@Composable
fun WearTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
            onSend = { onImeAction?.invoke() },
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 读取手表电量百分比。
 *
 * 通过订阅 `ACTION_BATTERY_CHANGED`（粘性广播）拿到实时电量，
 * 用于顶部/底部弧形状态栏展示。
 */
@Composable
fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(readBatteryPercent(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                percent = if (level >= 0 && scale > 0) level * 100 / scale else null
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Android 14 起注册非系统广播需要显式声明导出属性
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return percent
}

/** 同步读取一次当前电量（粘性广播，不会阻塞） */
private fun readBatteryPercent(context: Context): Int? {
    return runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else null
    }.getOrNull()
}
