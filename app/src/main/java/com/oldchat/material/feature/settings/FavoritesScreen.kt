package com.oldchat.material.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 收藏 — favorites list (e.g. bookmarked moments / content).
 * Mirrors §7.4 (收藏). The original backs favorites from user's saved items;
 * here we show an empty-friendly list backed by a favorites fetch, with a
 * refresh action. When the server has no favorites endpoint defined in the
 * guide, we display a clean empty state rather than a dead button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit = {}
) {
    var items by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun doLoad() {
        loading = true
        // Try fetching favorites; fall back to empty list on any error.
        try {
            OldChatApplication.instance.apiClient.get(
                "/me/favorites"
            ).fold(
                onSuccess = { json ->
                    items = parseFavorites(json)
                    loaded = true
                },
                onFailure = {
                    items = emptyList()
                    loaded = true
                }
            )
        } catch (_: Exception) {
            items = emptyList()
            loaded = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { doLoad() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when {
            loading && !loaded -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.BookmarkBorder, null, modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无收藏",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("收藏的动态和内容会显示在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            CoroutineScope(Dispatchers.Main).launch { doLoad() }
                        }) {
                            Text("刷新")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(items) { item ->
                        FavoriteRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(item: FavoriteItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                if (item.subtitle.isNotEmpty()) {
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class FavoriteItem(val id: String, val title: String, val subtitle: String = "")

private fun parseFavorites(json: String): List<FavoriteItem> {
    return try {
        val gson = OldChatApplication.instance.gson
        val trimmed = json.trim()
        val list = if (trimmed.startsWith("[")) {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            gson.fromJson<List<Map<String, Any>>>(trimmed, type) ?: emptyList()
        } else {
            val root = gson.fromJson(trimmed, Map::class.java) as? Map<*, *>
            (root?.get("data") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        }
        list.mapNotNull { m ->
            val id = (m["id"] ?: m["fid"])?.toString() ?: return@mapNotNull null
            FavoriteItem(
                id = id,
                title = (m["title"] ?: m["content"])?.toString() ?: "收藏",
                subtitle = m["subtitle"]?.toString() ?: ""
            )
        }
    } catch (_: Exception) { emptyList() }
}