package com.oldchat.material.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.cache.CacheManager
import com.oldchat.material.core.cache.DpiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full settings screen with all entries from ProfileFragment §7.4.
 * Includes: DPI adjustment, server config, dark mode, notification settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSettingsScreen(
    onBack: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val preferences = remember { OldChatApplication.instance.cacheManager.preferences }
    val dpiManager = remember { DpiManager(preferences) }

    val fontScale by dpiManager.fontScale.collectAsState(initial = 1.0f)
    val displayScale by dpiManager.displayScale.collectAsState(initial = 1.0f)
    val isDark by preferences.isDarkMode.collectAsState(initial = false)
    val receiveMode by preferences.messageReceiveMode.collectAsState(initial = "ws_priority")

    var showDpiDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showFilesServerDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showReceiveModeDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    val serverUrl by remember { mutableStateOf(OldChatApplication.instance.serverConfig.baseUrl) }

    // 缓存占用状态（后台 IO 计算）
    var cacheGroups by remember { mutableStateOf<List<CacheManager.CacheGroup>>(emptyList()) }
    var cacheTotal by remember { mutableStateOf(0L) }

    // 首次进入时计算一次缓存占用；清理后刷新
    fun refreshCacheStats() {
        val cacheManager = OldChatApplication.instance.cacheManager
        val appContext = OldChatApplication.instance.applicationContext
        scope.launch {
            val (groups, total) = withContext(Dispatchers.IO) {
                val g = cacheManager.buildCacheGroups(appContext)
                g to g.sumOf { it.bytes }
            }
            cacheGroups = groups
            cacheTotal = total
        }
    }
    LaunchedEffect(Unit) { refreshCacheStats() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
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
            // ---- Server Section ----
            item(key = "server_header") {
                SectionHeader("服务器")
            }
            item(key = "server_config") {
                SettingsRow(
                    Icons.Filled.Cloud,
                    "服务器地址",
                    subtitle = serverUrl,
                    onClick = { showServerDialog = true }
                )
            }
            item(key = "files_server") {
                SettingsRow(
                    Icons.Filled.FolderOpen,
                    "文件服务器",
                    subtitle = OldChatApplication.instance.serverConfig.filesBaseUrl.ifBlank { "未设置（跟随登录服务器）" },
                    onClick = { showFilesServerDialog = true }
                )
            }

            // ---- Appearance Section ----
            item(key = "appearance_header") {
                SectionHeader("外观")
            }
            item(key = "dark_mode") {
                SettingsToggleRow(
                    icon = Icons.Filled.DarkMode,
                    title = "深色模式",
                    checked = isDark,
                    onToggle = {
                        scope.launch { preferences.setDarkMode(it) }
                    }
                )
            }
            item(key = "dpi_scale") {
                SettingsRow(
                    Icons.Filled.Language,
                    "DPI 缩放",
                    subtitle = "字体 ${(fontScale * 100).toInt()}% · 界面 ${(displayScale * 100).toInt()}%",
                    onClick = { showDpiDialog = true }
                )
            }

            // ---- Notifications Section ----
            item(key = "notif_header") {
                SectionHeader("通知")
            }
            item(key = "message_notif") {
                SettingsToggleRow(
                    icon = Icons.Filled.Notifications,
                    title = "消息通知",
                    checked = true,
                    onToggle = { /* notification toggle stored in settings; kept default on */ }
                )
            }
            item(key = "receive_mode") {
                val modeLabel = com.oldchat.material.core.network.MessageReceiver.Mode.fromKey(receiveMode).label
                SettingsRow(
                    Icons.Filled.Wifi,
                    "消息接收方式",
                    subtitle = modeLabel,
                    onClick = { showReceiveModeDialog = true }
                )
            }

            // ---- Privacy Section ----
            item(key = "privacy_header") {
                SectionHeader("隐私与安全")
            }
            item(key = "privacy_settings") {
                SettingsRow(
                    Icons.Filled.Security, "隐私与安全", subtitle = "加密、会话管理",
                    onClick = { /* TODO: privacy detail */ }
                )
            }
            item(key = "burn_message") {
                SettingsRow(
                    Icons.Filled.Timer, "阅后即焚", subtitle = "焚毁消息设置",
                    onClick = { /* TODO: burn settings */ }
                )
            }

            // ---- Storage Section ----
            item(key = "storage_header") {
                SectionHeader("存储")
            }
            item(key = "manage_cache") {
                SettingsRow(
                    Icons.Filled.CleaningServices, "管理缓存",
                    subtitle = "缓存占用 ${formatBytes(cacheTotal)}",
                    onClick = { showCacheDialog = true }
                )
            }

            // ---- About Section ----
            item(key = "about_header") {
                SectionHeader("关于")
            }
            item(key = "about") {
                SettingsRow(
                    Icons.Filled.Info, "关于 OldChat Material", subtitle = "版本 2.3.4 (build 1) · Material You",
                    onClick = { showAboutDialog = true }
                )
            }
            item(key = "licenses") {
                SettingsRow(
                    Icons.Filled.Description, "开源许可", subtitle = "查看使用的开源库",
                    onClick = { showLicensesDialog = true }
                )
            }
            item(key = "check_update") {
                SettingsRow(
                    Icons.Filled.SystemUpdate, "检查更新", subtitle = "24h 间隔自动检查",
                    onClick = { /* already auto-checked */ }
                )
            }
            item(key = "feedback") {
                SettingsRow(
                    Icons.Filled.Feedback, "问题反馈", subtitle = "反馈使用中遇到的问题",
                    onClick = { showFeedbackDialog = true }
                )
            }

            // ---- Logout ----
            item(key = "logout_spacer") {
                Spacer(Modifier.height(16.dp))
            }
            item(key = "logout") {
                Button(
                    onClick = { showLogoutDialog = true },
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
        }
    }

    // DPI Dialog
    if (showDpiDialog) {
        DpiAdjustDialog(
            fontScale = fontScale,
            displayScale = displayScale,
            onFontScaleChange = { scope.launch { dpiManager.setFontScale(it) } },
            onDisplayScaleChange = { scope.launch { dpiManager.setDisplayScale(it) } },
            onDismiss = { showDpiDialog = false }
        )
    }

    // Server address dialog (§19.19 ServerBaseUrlManager)
    if (showServerDialog) {
        var url by remember { mutableStateOf(serverUrl) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("服务器地址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("修改 OldChat Material API 服务器地址。留空恢复默认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    OldChatApplication.instance.serverConfig.baseUrl = url.trim()
                    showServerDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text("取消") }
            }
        )
    }

    // Files server dialog
    if (showFilesServerDialog) {
        var url by remember { mutableStateOf(OldChatApplication.instance.serverConfig.filesBaseUrl) }
        AlertDialog(
            onDismissRequest = { showFilesServerDialog = false },
            title = { Text("文件服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("修改文件/媒体服务器地址（可选）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    OldChatApplication.instance.serverConfig.filesBaseUrl = url.trim()
                    showFilesServerDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showFilesServerDialog = false }) { Text("取消") }
            }
        )
    }

    // About dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于 OldChat Material") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OldChat Material", style = MaterialTheme.typography.titleMedium)
                    Text("版本 2.3.4 (build 1)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Material You 设计的第三方 OldChat Material 客户端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("WebSocket 实时消息的加密会话（ECDH + AES-CBC + HMAC）协议实现，参考 OldChat-For-Windows 开源项目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
            }
        )
    }

    // Licenses dialog
    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("开源许可") },
            text = {
                Text("本项目使用以下开源库：\n\n• Jetpack Compose / Material 3\n• Ktor / OkHttp\n• Coil\n• Gson / kotlinx.serialization\n• Media3\n\n均遵循各自的开源许可证。\n\n特别致谢：\n• OldChat-For-Windows（MIT License）\n  https://github.com/Coloryi-MIAO/OldChat-For-Windows\n  WebSocket 加密会话协议参考实现。",
                    style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("确定") }
            }
        )
    }

    // Feedback dialog（问题反馈）
    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            title = { Text("功能未完善") },
            text = {
                Text(
                    "如果您需要反馈问题，请下载官方客户端。如果问题复现，请在官方客户端中反馈。如果您需要反馈 OldChat Material 的问题，请等待本项目建立 GitHub 仓库，再提交 Issues。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showFeedbackDialog = false }) { Text("知道了") }
            }
        )
    }

    // Message receive mode dialog
    if (showReceiveModeDialog) {
        val modes = com.oldchat.material.core.network.MessageReceiver.Mode.entries
        var selected by remember { mutableStateOf(receiveMode) }
        AlertDialog(
            onDismissRequest = { showReceiveModeDialog = false },
            title = { Text("消息接收方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("选择接收消息的方式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    modes.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = mode.key }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == mode.key,
                                onClick = { selected = mode.key }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                Text(modeDescription(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { preferences.setMessageReceiveMode(selected) }
                    showReceiveModeDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showReceiveModeDialog = false }) { Text("取消") }
            }
        )
    }

    // 缓存管理对话框
    if (showCacheDialog) {
        var clearing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!clearing) showCacheDialog = false },
            title = { Text("管理缓存") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "缓存可提升打开速度，清理后下次加载稍慢，但不会影响账号与聊天记录发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    if (cacheGroups.isEmpty() && cacheTotal == 0L) {
                        Text("当前没有缓存数据。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        cacheGroups.forEach { g ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(g.name, style = MaterialTheme.typography.bodyLarge)
                                Text(formatBytes(g.bytes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("合计", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text(formatBytes(cacheTotal),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    if (clearing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !clearing && cacheTotal > 0L,
                    onClick = {
                        clearing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                OldChatApplication.instance.cacheManager.clearAppCache(
                                    OldChatApplication.instance.applicationContext
                                )
                            }
                            refreshCacheStats()
                            clearing = false
                            showCacheDialog = false
                        }
                    }
                ) { Text(if (clearing) "清理中…" else "一键清理") }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = { showCacheDialog = false }
                ) { Text("取消") }
            }
        )
    }

    // Logout confirm dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新登录才能使用。确认退出？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text("确认退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ---- DPI Adjust Dialog ----

@Composable
fun DpiAdjustDialog(
    fontScale: Float,
    displayScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onDisplayScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val fontPresets = listOf(0.75f, 0.875f, 1.0f, 1.15f, 1.25f, 1.5f)
    val displayPresets = listOf(0.75f, 0.875f, 1.0f, 1.15f, 1.25f, 1.5f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DPI 缩放") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Font scale
                Column {
                    Text("字体缩放：${(fontScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        fontPresets.forEach { preset ->
                            val isSelected = abs(fontScale - preset) < 0.01f
                            FilterChip(
                                selected = isSelected,
                                onClick = { onFontScaleChange(preset) },
                                label = { Text("${(preset * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Display scale
                Column {
                    Text("界面缩放：${(displayScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        displayPresets.forEach { preset ->
                            val isSelected = abs(displayScale - preset) < 0.01f
                            FilterChip(
                                selected = isSelected,
                                onClick = { onDisplayScaleChange(preset) },
                                label = { Text("${(preset * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

private fun abs(f: Float): Float = kotlin.math.abs(f)

// ---- Reusable Components ----

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * 消息接收方式的说明文本。
 */
private fun modeDescription(mode: com.oldchat.material.core.network.MessageReceiver.Mode): String {
    return when (mode) {
        com.oldchat.material.core.network.MessageReceiver.Mode.WS_PRIORITY ->
            "优先用 WebSocket 接收，断线时降级为每 5 秒 HTTP 轮询"
        com.oldchat.material.core.network.MessageReceiver.Mode.WS_ONLY ->
            "仅使用 WebSocket 接收消息"
        com.oldchat.material.core.network.MessageReceiver.Mode.HTTP_ONLY ->
            "仅使用 HTTP 每 5 秒轮询接收消息"
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
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

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

/**
 * 将字节数格式化为可读字符串（B / KB / MB / GB）。
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
