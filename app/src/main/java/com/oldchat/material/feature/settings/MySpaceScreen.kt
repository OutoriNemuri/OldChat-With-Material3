package com.oldchat.material.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.flow.first

/**
 * 我的空间 — personal space / home page display.
 * Mirrors §7.4 (我的空间). Reuses /me data; the full user-space API is defined
 * in the original (user_space_profile_cache SP), here we render the current user's
 * public profile with basic stats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySpaceScreen(
    onBack: () -> Unit = {}
) {
    // Load own profile JSON for space display.
    var profileJson by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val app = OldChatApplication.instance
        val result: Result<String> = try {
            app.apiClient.get("/me")
        } catch (_: Exception) {
            Result.failure(Exception("offline"))
        }
        if (result.isSuccess) {
            profileJson = result.getOrNull() ?: ""
        } else {
            // fall back to cached profile (suspend read is fine inside LaunchedEffect)
            profileJson = try {
                app.cacheManager.preferences.profileCacheJson.first()
            } catch (_: Exception) { "" }
        }
    }

    val gson = remember { com.google.gson.Gson() }
    val profileMap = remember(profileJson) {
        profileJson.takeIf { it.isNotBlank() }?.let { raw ->
            try {
                @Suppress("UNCHECKED_CAST")
                val root = gson.fromJson(raw, Map::class.java) as? Map<String, Any?>
                (root?.get("data") as? Map<String, Any?>) ?: root
            } catch (_: Exception) { null }
        }
    }
    val nickname = (profileMap?.get("display_name")
        ?: profileMap?.get("nickname") ?: profileMap?.get("name")) as? String ?: "用户"
    val uid = (profileMap?.get("uid") ?: profileMap?.get("user_id"))?.toString() ?: ""
    val avatarUrl = (profileMap?.get("avatar_url") ?: profileMap?.get("avatar")) as? String
    val resolvedAvatarUrl = remember(avatarUrl) {
        OldChatApplication.instance.serverConfig.resolveMediaUrl(avatarUrl)
    }
    val title = (profileMap?.get("user_title") ?: profileMap?.get("title")) as? String
    val signature = profileMap?.get("signature") as? String ?: "这个人很懒，什么都没留下"
    val presence = (profileMap?.get("presence_status") ?: profileMap?.get("online_status"))
        as? String ?: "online"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的空间") },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Space cover header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(
                        "个人空间",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Profile summary
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (resolvedAvatarUrl != null) {
                        AsyncImage(
                            model = resolvedAvatarUrl,
                            contentDescription = nickname,
                            modifier = Modifier.size(64.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(nickname.take(1),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(nickname, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("UID: $uid", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (title != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(title,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }
            }

            // Signature
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        signature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }

            // Status tile
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SpaceStat("在线状态", if (presence == "online") "在线" else "离线")
                    SpaceStat("信誉", strong = true)
                }
            }
        }
    }
}

@Composable
private fun SpaceStat(label: String, value: String = "--", strong: Boolean = false) {
    Card(modifier = Modifier.widthIn(min = 120.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                if (strong) "⭐" else value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}