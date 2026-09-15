package com.oldchat.material.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.feature.home.HomeViewModel

/**
 * 首次使用引导：本机没有任何缓存时，一次性把「基础数据」拉进本地缓存。
 *
 * 拉取内容（**有界**，避免触发服务端反滥用 §0）：
 *   · 通讯录：好友列表、群列表（含头像相对路径、群名）
 *   · 每个会话最新一条消息 → 落到会话列表预览
 *   · 每个会话少量历史（默认 10 条 / 最多 30 个会话 / 并发 4）
 *
 * 用户也可以选择「以后再说」，之后仍会随 WS/轮询自然补齐。
 */
@Composable
fun PrefetchGuideDialog(
    state: HomeViewModel.PrefetchState,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(28.dp)) },
        title = { Text("首次使用：建立本地缓存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "本机还没有任何缓存。可以现在一次性拉取少量基础数据，" +
                        "之后打开会话、切换页面会更快，也不必逐条等消息。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "只拉少量：每个会话最多 10 条，最多 30 个会话（不会全量刷服务器）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when {
                    state.running -> {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在拉取…好友 ${state.friends} · 群 ${state.groups} · 会话 ${state.previews}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    state.message != null -> {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStart, enabled = !state.running && !state.finished) {
                Text(if (state.finished) "已完成" else "开始拉取")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.running) {
                Text(if (state.finished) "关闭" else "以后再说")
            }
        }
    )
}
