package com.oldchat.material.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.BuildConfig
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.cache.CacheManager
import com.oldchat.material.core.cache.DpiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置（**入口页 / hub**）
 *
 * 结构变化：设置不再是一张长列表，而是「分组入口 → 各子界面」：
 *   服务器 → [ServerSettingsScreen]      （线路 / 文件服务器 / 生效地址）
 *   外观   → [AppearanceSettingsScreen]  （深色模式 / 动态取色 / DPI / 新闻区）
 *   通知   → [NotificationSettingsScreen]（总开关 / 声音 / 震动 / 接收方式）
 *   隐私   → [PrivacySettingsScreen]     （加密范围与本地数据说明）
 *   存储   → [CacheManagerScreen]        （分类 + 按会话清理）
 *   关于   → [AboutSettingsScreen]       （版本 / 许可 / 反馈）
 *
 * 每个子界面自带顶栏与返回，切换带左右滑动 + 淡入淡出动画；
 * 系统返回键在子界面内先回 hub，再退出设置（子界面的 BackHandler 优先于外层）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSettingsScreen(
    onBack: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val app = OldChatApplication.instance
    val preferences = remember { app.cacheManager.preferences }
    val dpiManager = remember { DpiManager(preferences) }

    // hub 上展示的摘要信息（改动后回到 hub 会实时刷新）
    val isDark by preferences.isDarkMode.collectAsState(initial = false)
    val useDynamicColor by preferences.useDynamicColor.collectAsState(initial = true)
    val notifEnabled by preferences.notificationsEnabled.collectAsState(initial = true)
    val receiveMode by preferences.messageReceiveMode.collectAsState(initial = "ws_priority")
    val fontScale by dpiManager.fontScale.collectAsState(initial = 1.0f)

    var route by remember { mutableStateOf<SettingsRoute?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(0L) }

    // 子界面打开时，系统返回键先回到 hub
    BackHandler(enabled = route != null) { route = null }

    // 缓存占用（进了缓存页回来会重新计算）
    LaunchedEffect(route) {
        if (route != null) return@LaunchedEffect
        cacheBytes = withContext(Dispatchers.IO) {
            app.cacheManager.listSections(app.applicationContext).sumOf { it.bytes }
        }
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (targetState == null) {
                // 返回 hub：从左侧滑入
                (slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(200)))
            } else {
                // 进入子界面：从右侧滑入
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut(tween(180)))
            }
        },
        label = "settings_nav"
    ) { current ->
        when (current) {
            SettingsRoute.SERVER -> ServerSettingsScreen(onBack = { route = null })
            SettingsRoute.APPEARANCE -> AppearanceSettingsScreen(onBack = { route = null })
            SettingsRoute.NOTIFICATION -> NotificationSettingsScreen(onBack = { route = null })
            SettingsRoute.PRIVACY -> PrivacySettingsScreen(onBack = { route = null })
            SettingsRoute.STORAGE -> CacheManagerScreen(onBack = { route = null })
            SettingsRoute.ABOUT -> AboutSettingsScreen(onBack = { route = null })
            null -> SettingsHub(
                isDark = isDark,
                useDynamicColor = useDynamicColor,
                notifEnabled = notifEnabled,
                receiveModeLabel = com.oldchat.material.core.network.MessageReceiver
                    .Mode.fromKey(receiveMode).label,
                fontScale = fontScale,
                cacheBytes = cacheBytes,
                onOpen = { route = it },
                onBack = onBack,
                onLogout = { showLogoutDialog = true }
            )
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新登录才能使用。确认退出？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) { Text("确认退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 设置分组 */
private enum class SettingsRoute { SERVER, APPEARANCE, NOTIFICATION, PRIVACY, STORAGE, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    isDark: Boolean,
    useDynamicColor: Boolean,
    notifEnabled: Boolean,
    receiveModeLabel: String,
    fontScale: Float,
    cacheBytes: Long,
    onOpen: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "server_header") { SectionHeader("连接") }
            item(key = "server") {
                SettingsRow(
                    Icons.Filled.Cloud,
                    "服务器",
                    subtitle = "${OldChatApplication.instance.serverConfig.mode.label} · " +
                        OldChatApplication.instance.serverConfig.businessBase(),
                    onClick = { onOpen(SettingsRoute.SERVER) }
                )
            }

            item(key = "appearance_header") { SectionHeader("外观与交互") }
            item(key = "appearance") {
                SettingsRow(
                    Icons.Filled.Palette,
                    "外观",
                    subtitle = buildString {
                        append(if (isDark) "深色" else "浅色")
                        append(if (useDynamicColor) " · 动态取色" else " · 内置配色")
                        append(" · 字体 ${(fontScale * 100).toInt()}%")
                    },
                    onClick = { onOpen(SettingsRoute.APPEARANCE) }
                )
            }
            item(key = "notification") {
                SettingsRow(
                    Icons.Filled.Notifications,
                    "通知",
                    subtitle = if (notifEnabled) "已开启 · $receiveModeLabel" else "已关闭 · $receiveModeLabel",
                    onClick = { onOpen(SettingsRoute.NOTIFICATION) }
                )
            }

            item(key = "privacy_header") { SectionHeader("数据与安全") }
            item(key = "privacy") {
                SettingsRow(
                    Icons.Filled.Security,
                    "隐私与安全",
                    subtitle = "加密范围、本地数据与缓存说明",
                    onClick = { onOpen(SettingsRoute.PRIVACY) }
                )
            }
            item(key = "storage") {
                SettingsRow(
                    Icons.Filled.CleaningServices,
                    "存储与缓存",
                    subtitle = "占用 ${formatBytes(cacheBytes)} · 可按分类/会话清理",
                    onClick = { onOpen(SettingsRoute.STORAGE) }
                )
            }

            item(key = "about_header") { SectionHeader("其它") }
            item(key = "about") {
                SettingsRow(
                    Icons.Filled.Info,
                    "关于",
                    subtitle = "版本 ${BuildConfig.VERSION_NAME} · 开源许可 · 问题反馈",
                    onClick = { onOpen(SettingsRoute.ABOUT) }
                )
            }

            item(key = "logout_spacer") { Spacer(Modifier.height(16.dp)) }
            item(key = "logout") {
                Button(
                    onClick = onLogout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Logout, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录")
                }
            }
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
