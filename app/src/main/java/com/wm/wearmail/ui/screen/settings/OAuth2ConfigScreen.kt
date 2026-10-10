package com.wm.wearmail.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wm.wearmail.core.AppContainer
import com.wm.wearmail.ui.kit.CircularItem
import com.wm.wearmail.ui.kit.CircularSafeLazyColumn
import com.wm.wearmail.ui.kit.CircularScreen
import com.wm.wearmail.ui.kit.SectionHeader
import com.wm.wearmail.ui.kit.WearIcons
import com.wm.wearmail.ui.kit.WearTextField
import com.wm.wearmail.ui.kit.rememberHaptics

/**
 * 文本框的最小高度：需求要求触控热区不小于 48x48dp
 * （与设置页/添加账户页的口径一致）。
 */
private val OAUTH2_FIELD_MIN_HEIGHT = 48.dp

/**
 * Outlook OAuth2 客户端 ID 配置页（覆盖层，从设置页进入）。
 *
 * 为什么独立成页：
 * - 客户端 ID / 租户是**低频、一次性**的配置（只在首次接入 Outlook 账户时需要），
 *   留在设置主列表里会让日常设置（同步/通知/存储）每次都要滚动经过两块输入框；
 * - 独立成页后设置页只保留一个入口按钮，主列表更短、常用项更快触达。
 *
 * 输入即保存（写入 [com.wm.wearmail.data.prefs.SettingsStore]），无需保存按钮；
 * 运行时取值优先级：设置值 > 构建期默认值（`gradle.properties`）。
 */
@Composable
fun OAuth2ConfigScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val vm: OAuth2ConfigViewModel = viewModel(factory = OAuth2ConfigViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    CircularScreen {
        CircularSafeLazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
        ) {
            var index = 0

            // ---------------- 标题 + 返回 ----------------
            oauthSlot(index++, listState) { modifier ->
                Row(
                    modifier = modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        haptics.tick()
                        onBack()
                    }) {
                        Icon(
                            imageVector = WearIcons.Back,
                            contentDescription = "返回设置",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Outlook OAuth2",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ---------------- 配置状态 ----------------
            oauthSlot(index++, listState) { modifier ->
                Text(
                    text = state.statusText,
                    modifier = modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.configured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---------------- 客户端 ID ----------------
            oauthSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    SectionHeader(text = "客户端 ID（Application ID）")
                    WearTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = OAUTH2_FIELD_MIN_HEIGHT),
                        value = state.clientId,
                        onValueChange = vm::setClientId,
                        placeholder = "例如 00000000-0000-0000-0000-000000000000",
                        singleLine = true,
                    )
                }
            }

            // ---------------- 租户 ----------------
            oauthSlot(index++, listState) { modifier ->
                Column(modifier = modifier.fillMaxWidth()) {
                    SectionHeader(text = "租户")
                    WearTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = OAUTH2_FIELD_MIN_HEIGHT),
                        value = state.tenant,
                        onValueChange = vm::setTenant,
                        placeholder = "common",
                        singleLine = true,
                    )
                }
            }

            // ---------------- Azure 注册指引 ----------------
            oauthSlot(index++, listState) { modifier ->
                Text(
                    text = "在 Azure 门户「应用注册」新建应用：账户类型选「任何组织目录中的账户和" +
                        "个人 Microsoft 账户」，并在「身份验证」里启用「允许公共客户端流」；" +
                        "把「应用程序(客户端) ID」填到上面。公共客户端 + 设备码流不需要客户端密码。" +
                        "以上内容输入后自动保存。",
                    modifier = modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 圆形安全列表项注册器（[index] 必须等于该项在 LazyColumn 中的真实索引）。
 */
private fun LazyListScope.oauthSlot(
    index: Int,
    listState: LazyListState,
    key: Any? = null,
    content: @Composable (Modifier) -> Unit,
) {
    item(key = key) {
        CircularItem(index = index, state = listState) { rowModifier ->
            content(rowModifier)
        }
    }
}
