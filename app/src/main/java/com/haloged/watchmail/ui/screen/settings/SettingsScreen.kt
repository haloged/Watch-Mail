package com.haloged.watchmail.ui.screen.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.SwitchDefaults
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ChevronRight
import com.haloged.watchmail.data.local.SettingsRepository
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel

/**
 * 设置页面
 *
 * 所有设置项通过 DataStore 持久化，并实时生效：
 *  - 同步频率 → 重排 WorkManager 周期任务
 *  - 通知开关 → 后台同步推送新邮件通知
 *  - 自动发草稿 → 联网后自动补发离线草稿
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAccountClick: () -> Unit,
    onDraftsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    // 从 ViewModel 读取持久化设置（DataStore）
    val settings by viewModel.settings.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    val draftsLabel = if (drafts.isEmpty()) "暂无草稿" else "${drafts.size} 封待发送"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopBar(onBack = onBack)

            // 设置内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .crownScroll(scrollState)   // 表冠滚动 + 刻度震动
                    .padding(horizontal = 16.dp)
            ) {
                // 账户管理
                SettingsSection(title = "账户") {
                    SettingsItem(
                        title = "邮箱账户管理",
                        subtitle = "添加、删除或编辑",
                        onClick = onAccountClick
                    )
                    SettingsItem(
                        title = "草稿箱",
                        subtitle = draftsLabel,
                        onClick = onDraftsClick
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 同步设置
                SettingsSection(title = "同步") {
                    // WorkManager PeriodicWork 最小 15 分钟，故下限为 15
                    SettingsStepper(
                        title = "同步频率",
                        value = settings.syncFrequencyMinutes,
                        valueRange = 15..60,
                        step = 5,
                        unit = "分钟",
                        onValueChange = { viewModel.setSyncFrequency(it) }
                    )

                    SettingsSwitch(
                        title = "自动发送草稿",
                        subtitle = "联网后补发",
                        checked = settings.autoSendDrafts,
                        onCheckedChange = { viewModel.setAutoSendDrafts(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 显示设置：界面大小（圆形表盘适配微调）
                SettingsSection(title = "显示") {
                    SettingsStepper(
                        title = "界面大小",
                        value = settings.uiScalePercent,
                        valueRange = SettingsRepository.UI_SCALE_MIN..SettingsRepository.UI_SCALE_MAX,
                        step = SettingsRepository.UI_SCALE_STEP,
                        unit = "%",
                        onValueChange = { viewModel.setUiScalePercent(it) }
                    )
                    Text(
                        text = "当前 ${settings.uiScale}x · 越小显示内容越多",
                        style = WatchMailTypography.Caption.copy(color = WatchMailColors.TextTertiary),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 通知设置
                SettingsSection(title = "通知") {
                    SettingsSwitch(
                        title = "新邮件通知",
                        subtitle = "振动提醒",
                        checked = settings.notificationsEnabled,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 关于
                SettingsSection(title = "关于") {
                    SettingsItem(
                        title = "版本",
                        subtitle = "1.0.0",
                        onClick = { }
                    )

                    SettingsItem(
                        title = "开发者",
                        subtitle = "WatchMail",
                        onClick = { }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 顶部栏
 */
@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WatchIconButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = WatchMailColors.TextPrimary
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "设置",
            style = WatchMailTypography.Title
        )
    }
}

/**
 * 设置分组
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = WatchMailTypography.BodySmall.copy(
                color = WatchMailColors.Primary,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(WatchMailColors.Surface)
                .padding(vertical = 4.dp)
        ) {
            content()
        }
    }
}

/**
 * 设置项（可点击）
 */
@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp) // 触控热区 ≥48dp
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = WatchMailTypography.ListItemTitle
            )

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = WatchMailTypography.ListItemSubtitle
                )
            }
        }

        androidx.wear.compose.material.Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = WatchMailColors.TextTertiary
        )
    }
}

/**
 * 设置开关
 */
@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp) // 触控热区 ≥48dp
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = WatchMailTypography.ListItemTitle
            )

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = WatchMailTypography.ListItemSubtitle
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WatchMailColors.Primary,
                checkedTrackColor = WatchMailColors.Primary.copy(alpha = 0.5f)
            )
        )
    }
}

/**
 * 设置步进器（左右箭头增减）
 * 替代滑块，更适合小屏精确调节
 */
@Composable
private fun SettingsStepper(
    title: String,
    value: Int,
    valueRange: IntRange,
    step: Int,
    unit: String,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = WatchMailTypography.ListItemTitle,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 减少（左箭头）
            WatchIconButton(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "减少",
                onClick = {
                    val next = (value - step).coerceIn(valueRange)
                    if (next != value) onValueChange(next)
                },
                tint = if (value > valueRange.first) WatchMailColors.Primary
                else WatchMailColors.TextTertiary,
                enabled = value > valueRange.first
            )

            Text(
                text = "$value $unit",
                style = WatchMailTypography.Title.copy(color = WatchMailColors.Primary)
            )

            // 增加（右箭头）
            WatchIconButton(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "增加",
                onClick = {
                    val next = (value + step).coerceIn(valueRange)
                    if (next != value) onValueChange(next)
                },
                tint = if (value < valueRange.last) WatchMailColors.Primary
                else WatchMailColors.TextTertiary,
                enabled = value < valueRange.last
            )
        }
    }
}
