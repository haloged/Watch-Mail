package com.haloged.watchmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.ui.theme.WatchMailColors
import com.haloged.watchmail.ui.theme.WatchMailTypography

/**
 * 账户列表项组件
 */
@Composable
fun AccountListItem(
    account: AccountEntity,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accountColor = WatchMailColors.getColorFromLong(account.color)
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(WatchMailColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户颜色圆点
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accountColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.alias.firstOrNull()?.toString() ?: "@",
                style = WatchMailTypography.Title.copy(
                    color = accountColor,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 账户信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.alias,
                style = WatchMailTypography.ListItemTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Text(
                text = account.email,
                style = WatchMailTypography.ListItemSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // 未读数量
        if (unreadCount > 0) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(WatchMailColors.Primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    style = WatchMailTypography.Caption.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

/**
 * 圆形筛选器按钮
 */
@Composable
fun FilterChip(
    label: String,
    isSelected: Boolean,
    color: Color = WatchMailColors.Primary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (isSelected) color else WatchMailColors.SurfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = WatchMailTypography.BodySmall.copy(
                color = if (isSelected) Color.White else WatchMailColors.TextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

/**
 * 状态指示器
 */
@Composable
fun StatusIndicator(
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            androidx.wear.compose.material.CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                indicatorColor = WatchMailColors.Primary,
                strokeWidth = 2.dp
            )
        }
    } else if (error != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WatchMailColors.Error.copy(alpha = 0.1f))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = error,
                style = WatchMailTypography.BodySmall.copy(color = WatchMailColors.Error),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 空状态提示
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "@",
                style = WatchMailTypography.TitleLarge.copy(
                    color = WatchMailColors.TextTertiary
                ),
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = message,
                style = WatchMailTypography.BodySmall,
                color = WatchMailColors.TextTertiary
            )
        }
    }
}
