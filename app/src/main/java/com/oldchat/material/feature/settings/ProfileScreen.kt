package com.oldchat.material.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import com.oldchat.material.feature.home.HomeViewModel
import com.oldchat.material.ui.theme.offlineStatus
import com.oldchat.material.ui.theme.onlineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Profile tab — user info, balance, credit, online status, and the four
 * entry points defined in client-guide.md §7.4:
 *   编辑资料 / 我的空间 / 收藏 / 设置.
 *
 * Inline sub-navigation via [profileRoute].
 */
private enum class ProfileRoute { EDIT_PROFILE, MY_SPACE, FAVORITES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    /** 设置页由 MainScreen 作为**独立整页**打开（不再嵌在本 tab 内） */
    onOpenSettings: () -> Unit = {},
    homeViewModel: HomeViewModel = viewModel()
) {
    var profileRoute by remember { mutableStateOf<ProfileRoute?>(null) }

    BackHandler(enabled = profileRoute != null) {
        profileRoute = null
    }

    AnimatedContent(
        targetState = profileRoute,
        transitionSpec = {
            if (targetState == null) {
                // 返回主界面：从左侧滑入
                (slideInHorizontally(initialOffsetX = { -it }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
            } else {
                // 进入子页面：从右侧滑入
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { -it }) + fadeOut())
            }
        },
        label = "profile_nav"
    ) { route ->
        when (route) {
            ProfileRoute.EDIT_PROFILE -> EditProfileScreen(onBack = { profileRoute = null })
            ProfileRoute.MY_SPACE -> MySpaceScreen(onBack = { profileRoute = null })
            ProfileRoute.FAVORITES -> FavoritesScreen(onBack = { profileRoute = null })
            // 设置不再是本页子路由：交给 MainScreen 以独立整页打开
            ProfileRoute.SETTINGS -> {
                LaunchedEffect(Unit) {
                    profileRoute = null
                    onOpenSettings()
                }
            }
            null -> ProfileMainScreen(
                homeViewModel = homeViewModel,
                onNavigate = { profileRoute = it },
                onLogout = onLogout,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun ProfileMainScreen(
    homeViewModel: HomeViewModel,
    onNavigate: (ProfileRoute) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profileJson by homeViewModel.profileJson.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        homeViewModel.loadProfile()
    }

    // Parse profile JSON — handles {nickname,...} and {data:{...}}.
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
    val uid = (profileMap?.get("uid") ?: profileMap?.get("user_id") ?: profileMap?.get("my_uid"))
        ?.toString() ?: OldChatApplication.instance.authManager.myUid ?: ""
    val avatarUrl = (profileMap?.get("avatar_url") ?: profileMap?.get("avatar")) as? String
    // Resolve the relative avatar path（动态跟随登录/文件服务器）。
    val resolvedAvatarUrl = remember(avatarUrl) {
        OldChatApplication.instance.serverConfig.resolveMediaUrl(avatarUrl)
    }
    val title = (profileMap?.get("user_title") ?: profileMap?.get("title")) as? String
    val credit = ((profileMap?.get("reputation_score")
        ?: profileMap?.get("credit_score") ?: profileMap?.get("credit"))
        as? Number)?.toInt() ?: 0
    val balance = ((profileMap?.get("coin_balance")
        ?: profileMap?.get("balance") ?: profileMap?.get("coins")) as? Number)?.toDouble() ?: 0.0
    val presenceStatus = (profileMap?.get("presence_status") ?: profileMap?.get("online_status"))
        as? String ?: "online"

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Profile header card
        item(key = "profile_header") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.size(80.dp)) {
                        if (resolvedAvatarUrl != null) {
                            AsyncImage(
                                model = resolvedAvatarUrl,
                                contentDescription = nickname,
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        nickname.take(1),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        // Online indicator
                        if (presenceStatus == "online") {
                            Surface(
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.BottomEnd),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .padding(3.dp),
                                    shape = CircleShape,
                                    color = onlineStatus
                                ) {}
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(nickname, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("UID: $uid", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (title != null) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                title,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("💰 旧币", "${balance.toInt()}")
                        StatItem("⭐ 信誉", "$credit")
                        StatItem(
                            if (presenceStatus == "online") "🟢 在线" else "⚫ 离线",
                            ""
                        )
                    }

                    // Online status toggle (§7.4: POST /me/presence)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val next = if (presenceStatus == "online") "invisible" else "online"
                            CoroutineScope(Dispatchers.Main).launch {
                                OldChatApplication.instance.apiClient.post(
                                    "/me/presence",
                                    OldChatApplication.instance.gson.toJson(
                                        mapOf("presence_status" to next)
                                    )
                                )
                                homeViewModel.loadProfile()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (presenceStatus == "online") "设为隐身" else "设为在线"
                        )
                    }
                }
            }
        }

        // Entry points (§7.4)
        item(key = "entries_header") {
            Text(
                "功能",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item(key = "edit_profile") {
            SettingsEntry(
                icon = Icons.Filled.Edit,
                title = "编辑资料",
                subtitle = "修改昵称、签名和头像",
                onClick = { onNavigate(ProfileRoute.EDIT_PROFILE) },
                modifier = Modifier.animateItem()
            )
        }
        item(key = "my_space") {
            SettingsEntry(
                icon = Icons.Filled.Public,
                title = "我的空间",
                subtitle = "查看个人主页",
                onClick = { onNavigate(ProfileRoute.MY_SPACE) },
                modifier = Modifier.animateItem()
            )
        }
        item(key = "favorites") {
            SettingsEntry(
                icon = Icons.Filled.Bookmark,
                title = "收藏",
                subtitle = "收藏的动态和内容",
                onClick = { onNavigate(ProfileRoute.FAVORITES) },
                modifier = Modifier.animateItem()
            )
        }
        item(key = "settings") {
            SettingsEntry(
                icon = Icons.Filled.Settings,
                title = "设置",
                subtitle = "服务器、外观、通知、隐私",
                onClick = { onNavigate(ProfileRoute.SETTINGS) },
                modifier = Modifier.animateItem()
            )
        }

        // Logout
        item(key = "logout") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        OldChatApplication.instance.authManager.clearAuth()
                        OldChatApplication.instance.cacheManager.clearAll()
                        onLogout()
                    },
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("退出登录", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ---- Stat Item ----

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNotEmpty()) {
            Text(value, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
    }
}

// ---- Settings Entry ----

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
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
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

