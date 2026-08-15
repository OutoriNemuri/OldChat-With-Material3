package com.oldchat.material.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.cache.EmojiStore

/**
 * 「我的表情」抽屉：磁贴式展示已保存的表情 + 一个添加按钮。
 * 点击表情 → onPick(mediaUrl) 触发发送；点添加 → 从本地相册选图加入「我的表情」。
 *
 * 表情持久化在 EmojiStore（本地 SharedPreferences），与广场解耦。
 */
@Composable
fun EmojiPickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val emojiStore = remember { OldChatApplication.instance.cacheManager.emojiStore }
    var emojis by remember { mutableStateOf(emojiStore.getAll()) }

    fun reload() { emojis = emojiStore.getAll() }

    val addLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 读取文件名作为标题，添加为本地表情
            var name = "表情"
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
            val rawUrl = uri.toString()
            emojiStore.add(
                EmojiStore.StoredEmoji(
                    id = "local_$rawUrl",
                    name = name,
                    mediaUrl = rawUrl
                )
            )
            reload()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("我的表情", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "关闭")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (emojis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有表情，点「+」添加或去表情广场保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 磁贴式展示：每行 6 个
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 添加按钮（磁贴之一）
                item(key = "add_emoji") {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { addLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, "添加表情",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                items(emojis, key = { it.id }) { emoji ->
                    EmojiTile(
                        emoji = emoji,
                        onClick = { onPick(emoji.mediaUrl) },
                        onLongDelete = { emojiStore.remove(emoji.id); reload() }
                    )
                }
            }
        }
    }
}

/**
 * 单个表情磁贴：点击发送，长按删除。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EmojiTile(
    emoji: EmojiStore.StoredEmoji,
    onClick: () -> Unit,
    onLongDelete: () -> Unit
) {
    val app = OldChatApplication.instance
    val url = remember(emoji.mediaUrl) {
        if (emoji.mediaUrl.startsWith("content://") || emoji.mediaUrl.startsWith("file://")) {
            emoji.mediaUrl
        } else {
            app.serverConfig.resolveMediaUrl(emoji.mediaUrl) ?: emoji.mediaUrl
        }
    }

    AsyncImage(
        model = url,
        contentDescription = emoji.name,
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongDelete() }
            )
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop
    )
}