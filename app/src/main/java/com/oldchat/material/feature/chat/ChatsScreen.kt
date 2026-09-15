package com.oldchat.material.feature.chat

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.core.model.MessagePayloadBuilder
import com.oldchat.material.core.model.RecentChatItem
import com.oldchat.material.core.network.WebSocketManager
import com.oldchat.material.feature.home.HomeViewModel
import com.oldchat.material.ui.theme.chatBubbleReceived
import com.oldchat.material.ui.theme.chatBubbleSent
import com.oldchat.material.ui.theme.onlineStatus
import com.oldchat.material.ui.theme.offlineStatus

/**
 * Chats tab — recent conversation list with Material You styling.
 * Mirrors ChatsFragment from client-guide.md §7.1.
 *
 * Sections: News (optional), Chats, Folded
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    modifier: Modifier = Modifier,
    onOpenChat: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenGroup: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenNotifications: () -> Unit = {},
    homeViewModel: HomeViewModel = viewModel()
) {
    val recentChats by homeViewModel.recentChats.collectAsStateWithLifecycle()
    val connectionState by homeViewModel.connectionState.collectAsStateWithLifecycle()
    // BUG-08：原来写死 true 并挂着 TODO —— 设置里的「首页显示新闻区」开关完全无效。
    val showNews by com.oldchat.material.OldChatApplication.instance.cacheManager.preferences
        .showNewsSection.collectAsStateWithLifecycle(initialValue = true)

    // 会话列表按最近消息时间降序排列（最新消息的会话排在最上）
    val sortedChats by remember(recentChats) {
        mutableStateOf(recentChats.sortedByDescending { it.lastTime })
    }

    // Load recent chats on first entry
    LaunchedEffect(Unit) {
        homeViewModel.refreshChats()
        homeViewModel.loadProfile()
    }

    // 首次使用（本机没有缓存）→ 引导一次性拉取基础数据进缓存
    var showPrefetchDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!homeViewModel.hasBasicCache()) showPrefetchDialog = true
    }
    val prefetchState by homeViewModel.prefetchState.collectAsStateWithLifecycle()
    if (showPrefetchDialog) {
        PrefetchGuideDialog(
            state = prefetchState,
            onStart = { homeViewModel.prefetchBasics() },
            onDismiss = {
                homeViewModel.dismissPrefetch()
                showPrefetchDialog = false
            }
        )
        // 预取完成 → 自动收起
        LaunchedEffect(prefetchState.finished) {
            if (prefetchState.finished) {
                kotlinx.coroutines.delay(1200)
                showPrefetchDialog = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Connection status banner — lightweight, shows only during reconnect attempts.
        // It is informational only; HTTP data loading works independently of WebSocket.
        if (connectionState == WebSocketManager.ConnectionState.CONNECTING) {
            item(key = "connecting") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (connectionState == WebSocketManager.ConnectionState.DISCONNECTED) {
            item(key = "disconnected") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            com.oldchat.material.OldChatApplication.instance.messageReceiver.start()
                            homeViewModel.refreshChats()
                            homeViewModel.refreshFriends()
                            homeViewModel.refreshGroups()
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "实时消息未连接 — 点击重连",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // News section (segment header)
        if (showNews) {
            item(key = "news_header") {
                SectionHeader(title = "📰 新闻", modifier = Modifier.animateItem())
            }
            item(key = "news_item") {
                NewsPlaceholderItem(
                    onClick = onOpenNotifications,
                    modifier = Modifier.animateItem()
                )
            }
        }

        // Chats section
        item(key = "chats_header") {
            SectionHeader(title = "聊天", modifier = Modifier.animateItem())
        }

        if (sortedChats.isEmpty()) {
            item(key = "empty_chats") {
                EmptyState(
                    icon = Icons.Filled.Chat,
                    message = "暂无聊天消息",
                    modifier = Modifier.animateItem()
                )
            }
        } else {
            items(
                items = sortedChats,
                key = { it.chatId }
            ) { chat ->
                ChatListItem(
                    chat = chat,
                    onClick = {
                        if (chat.type == "group") onOpenGroup(chat.chatId, chat.name, chat.avatarUrl)
                        else onOpenChat(chat.chatId, chat.name, chat.avatarUrl)
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

// ---- Section Header ----

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// ---- Chat List Item ----

@Composable
private fun ChatListItem(
    chat: RecentChatItem,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val previewText = remember(chat.lastMessage, chat.lastMessageType) {
        MessagePayloadBuilder.extractPreviewText(chat.lastMessageType, chat.lastMessage)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with online indicator
            Box(modifier = Modifier.size(52.dp)) {
                val resolvedAvatar = resolveChatAvatar(chat.avatarUrl)
                if (resolvedAvatar != null) {
                    AsyncImage(
                        model = resolvedAvatar,
                        contentDescription = chat.name,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        color = if (chat.type == "group")
                            MaterialTheme.colorScheme.tertiaryContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (chat.type == "group") Icons.Filled.Groups
                                else Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = if (chat.type == "group")
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Online dot
                if (chat.type == "direct" && chat.presenceStatus == "online") {
                    Surface(
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(10.dp)
                                .padding(2.dp),
                            shape = CircleShape,
                            color = onlineStatus
                        ) {}
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chat.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTime(chat.lastTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (chat.unreadCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(
                                text = if (chat.unreadCount > 99) "99+"
                                else chat.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else if (chat.isPinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ---- News Placeholder ----

@Composable
private fun NewsPlaceholderItem(onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Newspaper, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("查看最新动态", style = MaterialTheme.typography.titleSmall)
                Text("来自 OldChat 社区", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Empty State ----

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ---- Helpers ----

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 86400 -> "${diff / 3600}小时前"
        diff < 604800 -> "${diff / 86400}天前"
        else -> {
            val sdf = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp * 1000))
        }
    }
}

/**
 * 会话头像相对路径 → 完整 URL（动态跟随登录/文件服务器）。
 * 兼容：完整 http(s) URL、/v1/uploads/... 相对路径、纯文件名。
 */
private fun resolveChatAvatar(path: String?): String? {
    return com.oldchat.material.OldChatApplication.instance.serverConfig.resolveMediaUrl(path)
}

