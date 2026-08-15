package com.oldchat.material.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Discover tab — entry points for Moments, Emoji Plaza, Music, News, etc.
 * Mirrors DiscoverFragment from client-guide.md §8.2.
 *
 * @param onNavigate callback with route name when an entry is tapped.
 */
@Composable
fun DiscoverScreen(
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {}
) {
    val entries = discoverEntries.map { entry ->
        entry.copy(
            onClick = { onNavigate(entry.route) }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(entries) { entry ->
            DiscoverEntry(
                icon = entry.icon,
                title = entry.title,
                subtitle = entry.subtitle,
                modifier = Modifier.animateItem(),
                onClick = entry.onClick
            )
        }
    }
}

// ---- Discover Entry ----

@Composable
private fun DiscoverEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- Entry Data ----

data class DiscoverEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val route: String = "",
    val onClick: () -> Unit = {}
)

private val discoverEntries = listOf(
    DiscoverEntry(Icons.Outlined.PhotoCamera, "动态", "浏览和发布朋友圈动态", route = "feed"),
    DiscoverEntry(Icons.Outlined.EmojiEmotions, "表情广场", "发现和分享精彩表情", route = "sticker_store"),
    DiscoverEntry(Icons.Outlined.MusicNote, "音乐广场", "发现和分享好音乐", route = "music_square"),
    DiscoverEntry(Icons.Outlined.Newspaper, "极简新闻", "浏览精选新闻资讯", route = "news"),
    DiscoverEntry(Icons.Outlined.PlayCircle, "OldView", "B 站视频集成", route = "oldview"),
    DiscoverEntry(Icons.Outlined.CheckCircle, "签到墙", "每日签到，领取奖励", route = "checkin"),
    DiscoverEntry(Icons.Outlined.AttachMoney, "刮刮乐", "每日刮奖，赢取旧币", route = "scratch_card"),
    DiscoverEntry(Icons.Outlined.Flag, "举报进度", "查看举报处理进度", route = "report_progress"),
    DiscoverEntry(Icons.Outlined.Gavel, "公开法庭", "查看社区裁决案例", route = "public_court"),
    DiscoverEntry(Icons.Outlined.Widgets, "CIP 小程序", "小程序 / 开发 / VibeCoding", route = "cip_center"),
    DiscoverEntry(Icons.Outlined.Settings, "发现设置", "管理发现页显示项", route = "discover_settings"),
)

