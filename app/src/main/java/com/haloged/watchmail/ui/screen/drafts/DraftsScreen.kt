package com.haloged.watchmail.ui.screen.drafts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.haloged.watchmail.data.local.entity.DraftEntity
import com.haloged.watchmail.ui.components.EmptyState
import com.haloged.watchmail.ui.components.WatchIconButton
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography
import com.haloged.watchmail.ui.util.crownScroll
import com.haloged.watchmail.ui.viewmodel.MainViewModel
import com.haloged.watchmail.util.HapticUtil
import java.text.SimpleDateFormat
import java.util.*

/**
 * 草稿箱页面
 * 展示离线草稿，支持继续编辑 / 立即发送 / 删除
 */
@Composable
fun DraftsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDraftClick: (DraftEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val drafts by viewModel.drafts.collectAsState()
    val view = LocalView.current
    val draftListState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WatchMailColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            TopBar(onBack = onBack)

            // 草稿列表
            if (drafts.isEmpty()) {
                EmptyState(
                    message = "暂无草稿",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = draftListState,
                    modifier = Modifier
                        .weight(1f)
                        .crownScroll(draftListState),   // 表冠滚动 + 刻度震动
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(items = drafts, key = { it.id }) { draft ->
                        DraftItem(
                            draft = draft,
                            onClick = {
                                HapticUtil.performLightFeedback(view)
                                onDraftClick(draft)
                            },
                            onSendNow = {
                                HapticUtil.performConfirmFeedback(view)
                                viewModel.sendDraft(draft)
                            },
                            onDelete = {
                                HapticUtil.performRejectFeedback(view)
                                viewModel.deleteDraft(draft.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        WatchIconButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = WatchMailColors.TextPrimary
        )

        Text(text = "草稿箱", style = WatchMailTypography.Title)

        // 占位，保持标题居中
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun DraftItem(
    draft: DraftEntity,
    onClick: () -> Unit,
    onSendNow: () -> Unit,
    onDelete: () -> Unit
) {
    val formattedTime = remember(draft.updatedAt) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(draft.updatedAt))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WatchMailColors.Surface)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "收件人: ${draft.toAddress.ifBlank { "(未填写)" }}",
                    style = WatchMailTypography.ListItemSubtitle.copy(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = draft.subject.ifBlank { "(无主题)" },
                    style = WatchMailTypography.ListItemTitle.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (draft.failCount > 0) {
                        "发送失败${draft.failCount}次 · $formattedTime"
                    } else {
                        formattedTime
                    },
                    style = WatchMailTypography.EmailTime.copy(fontSize = 14.sp),
                    maxLines = 1,
                    color = if (draft.failCount > 0) WatchMailColors.Error else WatchMailColors.TextTertiary
                )
            }

            // 立即发送
            WatchIconButton(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "立即发送",
                onClick = onSendNow,
                tint = WatchMailColors.Primary
            )

            // 删除草稿
            WatchIconButton(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除草稿",
                onClick = onDelete,
                tint = WatchMailColors.Error
            )
        }
    }
}
