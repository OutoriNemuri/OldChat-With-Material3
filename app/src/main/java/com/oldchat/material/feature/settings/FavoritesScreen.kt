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
import kotlinx.coroutines.launch

/**
 * 收藏 — §7.4 / routes.md。
 *
 * 服务端路由（routes.md:316-318）：
 *   GET  /v1/favorites
 *   POST /v1/favorites/add     { target_type, target_id }
 *   POST /v1/favorites/remove  { target_type, target_id }
 * ApiClient 的 base 已含 /v1，所以这里传 "/favorites"。
 *
 * BUG-01 修复：原先请求 /me/favorites（不存在 → 404），且解析里读 root["data"]，
 * 而服务端实际返回的是 items，导致页面永远空白且错误被吞掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val app = OldChatApplication.instance

    var items by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun doLoad() {
        loading = true
        error = null
        try {
            app.apiClient.get("/favorites").fold(
                onSuccess = { json ->
                    items = parseFavorites(json)
                    loaded = true
                },
                onFailure = { e ->
                    // 不再静默吞掉：把失败原因显示出来，便于区分「真的没有收藏」和「接口失败」
                    error = e.message ?: "加载失败"
                    loaded = true
                }
            )
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
            loaded = true
        } finally {
            loading = false
        }
    }

    suspend fun doRemove(item: FavoriteItem) {
        val body = app.gson.toJson(
            mapOf("target_type" to item.targetType, "target_id" to item.id)
        )
        app.apiClient.post("/favorites/remove", body).fold(
            onSuccess = { items = items.filterNot { it.id == item.id && it.targetType == item.targetType } },
            onFailure = { error = it.message ?: "取消收藏失败" }
        )
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
                actions = {
                    IconButton(onClick = { scope.launch { doLoad() } }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, "刷新")
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
            error != null && items.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.ErrorOutline, null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                        Spacer(Modifier.height(12.dp))
                        Text("收藏加载失败", style = MaterialTheme.typography.bodyLarge)
                        Text(error ?: "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { scope.launch { doLoad() } }) { Text("重试") }
                    }
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
                        TextButton(onClick = { scope.launch { doLoad() } }) { Text("刷新") }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(items, key = { "${it.targetType}_${it.id}" }) { item ->   // BUG-19：补 key
                        FavoriteRow(
                            item = item,
                            onRemove = { scope.launch { doRemove(item) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(item: FavoriteItem, onRemove: () -> Unit) {
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
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                if (item.subtitle.isNotEmpty()) {
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.DeleteOutline, "取消收藏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class FavoriteItem(
    val id: String,
    val targetType: String,
    val title: String,
    val subtitle: String = ""
)

/**
 * 解析 GET /v1/favorites 的响应。
 * 服务端字段为 items（不是 data），每条含 target_type / target_id；
 * 兼容旧字段 item_type / item_id 与 id 形式，避免再次静默空白。
 */
private fun parseFavorites(json: String): List<FavoriteItem> {
    return try {
        val gson = OldChatApplication.instance.gson
        val trimmed = json.trim()
        val list: List<*> = if (trimmed.startsWith("[")) {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            gson.fromJson<List<Map<String, Any>>>(trimmed, type) ?: emptyList<Any>()
        } else {
            val root = gson.fromJson(trimmed, Map::class.java) as? Map<*, *>
            val raw = root?.get("items") ?: root?.get("data") ?: root?.get("list")
            (raw as? List<*>) ?: emptyList<Any>()
        }
        list.filterIsInstance<Map<*, *>>().mapNotNull { m ->
            val id = (m["target_id"] ?: m["item_id"] ?: m["id"] ?: m["fid"])?.toString()
                ?: return@mapNotNull null
            val type = (m["target_type"] ?: m["item_type"] ?: m["type"])?.toString() ?: "moment"
            FavoriteItem(
                id = id,
                targetType = type,
                title = (m["title"] ?: m["content"] ?: m["preview"])?.toString() ?: "收藏",
                subtitle = (m["subtitle"] ?: m["author_name"] ?: m["created_at"])?.toString() ?: ""
            )
        }
    } catch (_: Exception) { emptyList() }
}
