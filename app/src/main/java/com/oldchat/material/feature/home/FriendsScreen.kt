package com.oldchat.material.feature.home

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
import com.oldchat.material.core.model.User
import com.oldchat.material.core.model.Group
import com.oldchat.material.ui.theme.offlineStatus
import com.oldchat.material.ui.theme.onlineStatus

/**
 * Friends tab — friends list + groups + friend requests.
 * Mirrors FriendsFragment from client-guide.md §7.3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    modifier: Modifier = Modifier,
    onOpenChat: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenGroup: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenNotifications: () -> Unit = {},
    homeViewModel: HomeViewModel = viewModel()
) {
    val friends by homeViewModel.friends.collectAsStateWithLifecycle()
    val groups by homeViewModel.groups.collectAsStateWithLifecycle()
    val friendRequestCount by homeViewModel.friendRequestCount.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        homeViewModel.refreshFriends()
        homeViewModel.refreshGroups()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Friend requests
        if (friendRequestCount > 0) {
            item(key = "friend_requests") {
                FriendRequestItem(count = friendRequestCount,
                    modifier = Modifier.animateItem())
            }
        }

        // System notifications entry
        item(key = "system_notif") {
            SystemNotificationEntry(
                onClick = onOpenNotifications,
                modifier = Modifier.animateItem()
            )
        }

        // Friends section
        item(key = "friends_header") {
            Text(
                "好友",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (friends.isEmpty()) {
            item(key = "empty_friends") {
                EmptyState(
                    icon = Icons.Filled.PersonOff,
                    message = "还没有好友，去发现页添加吧",
                    modifier = Modifier.animateItem()
                )
            }
        } else {
            items(friends, key = { it.uid }) { user ->
                FriendListItem(
                    user = user,
                    onClick = { onOpenChat(user.uid, user.nickname, user.avatarUrl) },
                    modifier = Modifier.animateItem()
                )
            }
        }

        // Groups section
        item(key = "groups_header") {
            Text(
                "群聊",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (groups.isEmpty()) {
            item(key = "empty_groups") {
                EmptyState(
                    icon = Icons.Filled.Groups,
                    message = "还没有群聊",
                    modifier = Modifier.animateItem()
                )
            }
        } else {
            items(groups, key = { it.id }) { group ->
                GroupListItem(
                    group = group,
                    onClick = { onOpenGroup(group.id, group.name, group.avatarUrl) },
                    modifier = Modifier.animateItem()
                )
            }
        }

        // Space for FAB
        item(key = "spacer") {
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ---- Friend Request Item ----

@Composable
private fun FriendRequestItem(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { /* TODO: Navigate to friend requests */ },
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BadgedBox(
                badge = {
                    Badge { Text(count.toString()) }
                }
            ) {
                Icon(
                    Icons.Filled.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "新的好友请求",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- System Notification Entry ----

@Composable
private fun SystemNotificationEntry(onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Notifications,
                        null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("系统通知", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Friend List Item ----

@Composable
private fun FriendListItem(
    user: User,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(modifier = Modifier.size(48.dp)) {
                val resolvedAvatar = resolveAvatarUrl(user.avatarUrl)
                if (resolvedAvatar != null) {
                    AsyncImage(
                        model = resolvedAvatar,
                        contentDescription = user.nickname,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                user.nickname.take(1),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                // Online dot
                if (user.presenceStatus == "online") {
                    Surface(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(8.dp)
                                .padding(1.5.dp),
                            shape = CircleShape,
                            color = onlineStatus
                        ) {}
                    }
                }
            }

            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (user.title != null) {
                    Text(
                        user.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- Group List Item ----

@Composable
private fun GroupListItem(
    group: Group,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                val resolvedAvatar = resolveAvatarUrl(group.avatarUrl)
                if (resolvedAvatar != null) {
                    AsyncImage(
                        model = resolvedAvatar,
                        contentDescription = group.name,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Groups,
                            null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${group.memberCount} 名成员",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Empty State (reused) ----

/**
 * Resolve a (possibly relative) avatar/media URL（动态跟随登录/文件服务器）。
 */
private fun resolveAvatarUrl(url: String?): String? =
    com.oldchat.material.OldChatApplication.instance.serverConfig.resolveMediaUrl(url)

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

