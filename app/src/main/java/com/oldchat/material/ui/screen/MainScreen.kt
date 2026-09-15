package com.oldchat.material.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.oldchat.material.feature.chat.ChatScreen
import com.oldchat.material.feature.settings.FullSettingsScreen
import com.oldchat.material.feature.chat.ChatsScreen
import com.oldchat.material.feature.chat.GroupChatScreen
import com.oldchat.material.feature.discover.CheckinScreen
import com.oldchat.material.feature.discover.CipCenterScreen
import com.oldchat.material.feature.discover.DiscoverScreen
import com.oldchat.material.feature.discover.DiscoverSettingsScreen
import com.oldchat.material.feature.discover.EmojiPlazaScreen
import com.oldchat.material.feature.discover.FeedScreen
import com.oldchat.material.feature.discover.MusicSquareScreen
import com.oldchat.material.feature.discover.PublicCourtScreen
import com.oldchat.material.feature.discover.ReportProgressScreen
import com.oldchat.material.feature.discover.ScratchCardScreen
import com.oldchat.material.feature.home.FriendsScreen
import com.oldchat.material.feature.home.HomeViewModel
import com.oldchat.material.feature.home.NotificationsScreen
import com.oldchat.material.feature.home.NotificationsViewModel
import com.oldchat.material.feature.settings.ProfileScreen
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.network.MessageReceiver
import com.oldchat.material.core.network.WebSocketManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Main screen with bottom navigation bar.
 * Four tabs: Chats / Friends / Discover / Profile (mirrors original §7).
 *
 * Discover tab and chat detail both use a sub-navigation stack. Back button
 * returns to the previous level instead of exiting the app.
 */
data class BottomNavItem(
    val label: String,
    val icon: ImageVector
)

/** A sub-page currently being shown (either a discover route or a chat). */
private data class SubRoute(val kind: String, val id: String, val name: String, val avatar: String? = null)

private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_NOTIFICATIONS = "notifications"

/**
 * 统一页面过渡：
 *   · 进入更深一层（tab → 设置/通知）→ 从右滑入 + 淡入，旧页轻微左移淡出
 *   · 返回（设置/通知 → tab）→ 从左滑入 + 淡入，旧页向右滑出
 *   · 同级 Tab 之间 → 按 Tab 序号方向轻微横移 + 淡入淡出（不会全屏横飞）
 */
private fun AnimatedContentTransitionScope<String>.appScreenTransition(): ContentTransform {
    val from = initialState
    val to = targetState
    val fromDepth = if (from.startsWith("tab:")) 0 else 1
    val toDepth = if (to.startsWith("tab:")) 0 else 1

    return when {
        toDepth > fromDepth ->
            (slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(targetOffsetX = { -it / 5 }) + fadeOut(tween(180)))

        toDepth < fromDepth ->
            (slideInHorizontally(initialOffsetX = { -it / 5 }) + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(240)))

        else -> {
            val fromTab = from.removePrefix("tab:").toIntOrNull() ?: 0
            val toTab = to.removePrefix("tab:").toIntOrNull() ?: 0
            val dir = if (toTab >= fromTab) 1 else -1
            (slideInHorizontally(initialOffsetX = { dir * it / 5 }) + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(targetOffsetX = { -dir * it / 5 }) + fadeOut(tween(180)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit = {},
    openMusicTitle: String? = null
) {
    val navItems = listOf(
        BottomNavItem("聊天", Icons.AutoMirrored.Filled.Chat),
        BottomNavItem("好友", Icons.Filled.Person),
        BottomNavItem("发现", Icons.Filled.Explore),
        BottomNavItem("我的", Icons.Outlined.Diamond)
    )

    var selectedTab by remember { mutableIntStateOf(0) }
    var discoverRoute by remember { mutableStateOf<String?>(null) }
    // Chat sub-navigation: non-null when a direct/group chat detail is open.
    var chatRoute by remember { mutableStateOf<SubRoute?>(null) }
    // 系统通知子页面开关
    var showNotifications by remember { mutableStateOf(false) }
    // 设置：作为**独立整页**（不再嵌在「我的」tab 内，避免顶栏套顶栏）
    var showSettings by remember { mutableStateOf(false) }
    // 从聊天音乐消息/音乐通知跳转到音乐广场时要播放的歌名
    var pendingMusicTitle by remember { mutableStateOf<String?>(null) }

    // 点击音乐通知进入"正在播放"界面：切到发现 Tab 并打开音乐广场，自动定位当前歌曲
    val pendingFromNotification = com.oldchat.material.MainActivity.pendingOpenMusicTitle
    val hasNotificationRequest = openMusicTitle?.isNotEmpty() == true ||
        pendingFromNotification?.isNotEmpty() == true
    LaunchedEffect(openMusicTitle, pendingFromNotification) {
        if (!hasNotificationRequest) return@LaunchedEffect
        // 优先用通知带过来的歌名，其次用当前正在播放的歌
        val openTitle = openMusicTitle?.takeIf { it.isNotEmpty() }
            ?: pendingFromNotification?.takeIf { it.isNotEmpty() }
            ?: com.oldchat.material.feature.discover.MusicPlayerHolder.currentTitle.takeIf { it.isNotEmpty() }
        if (openTitle != null) {
            pendingMusicTitle = openTitle
            selectedTab = 2
            discoverRoute = "music_square"
        }
    }

    // 系统通知 ViewModel（列表 + 启动弹窗）
    val notificationsViewModel: NotificationsViewModel = viewModel()

    // HomeViewModel（复用 ChatsScreen/FriendsScreen 同一实例，用于清未读数）
    val homeViewModel: HomeViewModel = viewModel()

    // 当前消息连接方式：WebSocket 或 HTTP（聊天标题下方显示）
    val app = OldChatApplication.instance
    val wsState by app.wsManager.connectionState.collectAsStateWithLifecycle()
    val receiveMode by app.messageReceiver.currentMode.collectAsStateWithLifecycle()
    val connectionLabel = deriveConnectionLabel(receiveMode, wsState)

    fun goBack() {
        if (chatRoute != null) chatRoute = null
        else if (showSettings) showSettings = false
        else if (discoverRoute != null) discoverRoute = null
        else if (showNotifications) showNotifications = false
    }

    // Intercept system back to return to previous level first.
    BackHandler(
        enabled = chatRoute != null || discoverRoute != null ||
            showNotifications || showSettings
    ) {
        goBack()
    }

    // 进入聊天详情时清空该会话未读数（单聊/群聊分别处理）
    LaunchedEffect(chatRoute?.kind, chatRoute?.id) {
        val route = chatRoute ?: return@LaunchedEffect
        if (route.kind == "group") {
            homeViewModel.clearGroupUnread(route.id)
        } else {
            homeViewModel.clearUnread(route.id)
        }
    }

    Scaffold(
        topBar = {
            if (chatRoute == null && discoverRoute == null && !showNotifications && !showSettings) {
                TopAppBar(
                    title = {
                        val titleText = when (selectedTab) {
                            0 -> "OldChat Material"
                            1 -> "好友"
                            2 -> "发现"
                            3 -> "我的"
                            else -> "OldChat Material"
                        }
                        if (selectedTab == 0) {
                            Column {
                                Text(titleText, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    connectionLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(titleText)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (chatRoute == null && discoverRoute == null && !showNotifications && !showSettings) {
                NavigationBar {
                    navItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = selectedTab == index,
                            onClick = { selectedTab = index }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when {
            // Chat detail is open — show it above everything.
            chatRoute != null -> {
                AnimatedContent(
                    targetState = chatRoute,
                    transitionSpec = {
                        // 进入聊天详情：从右侧滑入 + 淡入；返回：滑出 + 淡出
                        (slideInHorizontally(initialOffsetX = { it }) + fadeIn()) togetherWith
                            (slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
                    },
                    label = "chat_nav"
                ) { route ->
                    if (route!!.kind == "group") {
                        GroupChatScreen(
                            groupId = route.id,
                            groupName = route.name,
                            onBack = { goBack() },
                            onOpenMusic = { title ->
                                pendingMusicTitle = title
                                chatRoute = null
                                selectedTab = 2
                                discoverRoute = "music_square"
                            }
                        )
                    } else {
                        ChatScreen(
                            friendUid = route.id,
                            friendName = route.name,
                            friendAvatarUrl = route.avatar,
                            onBack = { goBack() },
                            onOpenMusic = { title ->
                                pendingMusicTitle = title
                                chatRoute = null
                                selectedTab = 2
                                discoverRoute = "music_square"
                            }
                        )
                    }
                }
            }
            // 其余界面（Tab / 设置 / 通知中心）共用一个方向感知的 AnimatedContent：
            // 进入子页从右滑入、返回向右滑出、同级 Tab 之间按方向轻微横移 + 淡入淡出。
            else -> {
                val screenKey = when {
                    showSettings -> SCREEN_SETTINGS
                    showNotifications -> SCREEN_NOTIFICATIONS
                    else -> "tab:$selectedTab"
                }
                AnimatedContent(
                    targetState = screenKey,
                    transitionSpec = { appScreenTransition() },
                    label = "app_nav"
                ) { key ->
                    when {
                        key == SCREEN_SETTINGS -> FullSettingsScreen(
                            onBack = { showSettings = false },
                            onLogout = onLogout
                        )
                        key == SCREEN_NOTIFICATIONS -> NotificationsScreen(
                            onBack = { showNotifications = false },
                            viewModel = notificationsViewModel
                        )
                        else -> when (key.removePrefix("tab:").toIntOrNull() ?: 0) {
                0 -> ChatsScreen(
                    modifier = Modifier.padding(innerPadding),
                    onOpenChat = { id, name, avatar -> chatRoute = SubRoute("direct", id, name, avatar) },
                    onOpenGroup = { id, name, avatar -> chatRoute = SubRoute("group", id, name, avatar) },
                    onOpenNotifications = { showNotifications = true }
                )
                1 -> FriendsScreen(
                    modifier = Modifier.padding(innerPadding),
                    onOpenChat = { id, name, avatar -> chatRoute = SubRoute("direct", id, name, avatar) },
                    onOpenGroup = { id, name, avatar -> chatRoute = SubRoute("group", id, name, avatar) },
                    onOpenNotifications = { showNotifications = true }
                )
                2 -> {
                    AnimatedContent(
                        targetState = discoverRoute,
                        transitionSpec = {
                            if (targetState == null) {
                                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                            } else {
                                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                            }
                        },
                        label = "discover_nav"
                    ) { route ->
                        when (route) {
                            "discover_settings" -> DiscoverSettingsScreen(onBack = { discoverRoute = null })
                            null -> DiscoverScreen(
                                modifier = Modifier.padding(innerPadding),
                                onNavigate = { discoverRoute = it }
                            )
                            "feed" -> FeedScreen(onBack = { discoverRoute = null })
                            "sticker_store" -> EmojiPlazaScreen(onBack = { discoverRoute = null })
                            "music_square" -> MusicSquareScreen(
                                onBack = {
                                    discoverRoute = null
                                    pendingMusicTitle = null
                                },
                                initialPlayTitle = pendingMusicTitle
                            )
                            "checkin" -> CheckinScreen(onBack = { discoverRoute = null })
                             "scratch_card" -> ScratchCardScreen(onBack = { discoverRoute = null })
                             "report_progress" -> ReportProgressScreen(onBack = { discoverRoute = null })
                             "public_court" -> PublicCourtScreen(onBack = { discoverRoute = null })
                             "cip_center" -> CipCenterScreen(onBack = { discoverRoute = null })
                             else -> UnderConstructionScreen(
                                title = route,
                                onBack = { discoverRoute = null },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
                3 -> ProfileScreen(
                    modifier = Modifier.padding(innerPadding),
                    onLogout = onLogout,
                    onOpenSettings = { showSettings = true }
                )
                        }   // 关闭 tab when
                    }       // 关闭 key when
                }           // 关闭 AnimatedContent
            }               // 关闭 else
        }
    }

    // 启动时拉取系统通知（决定是否弹重要通知）
    LaunchedEffect(Unit) { notificationsViewModel.loadIfEmpty() }

    // 重要通知弹窗
    val importantNotice by notificationsViewModel.importantNotice.collectAsStateWithLifecycle()
    importantNotice?.let { notice ->
        ImportantNoticeDialog(
            notice = notice,
            onDismiss = { notificationsViewModel.dismissImportantNotice(notice.id) }
        )
    }
}

/**
 * 重要通知弹窗：显示最新一条 important 通知的标题 + 内容，
 * 带"不再提示"勾选框；勾选后记录该条 id，直到有新 important 消息才再提示。
 */
@Composable
private fun ImportantNoticeDialog(
    notice: com.oldchat.material.feature.home.SystemNotification,
    onDismiss: () -> Unit
) {
    var checked by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (checked) onDismiss() },
        title = { Text(notice.title.ifEmpty { "重要通知" }) },
        text = {
            Column {
                Text(notice.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { checked = !checked }
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { checked = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("不再提示", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

/**
 * Placeholder for unimplemented discover sub-pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnderConstructionScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(routeToTitle(title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "该功能开发中",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun routeToTitle(route: String): String {
    return when (route) {
        "sticker_store" -> "表情广场"
        "news" -> "极简新闻"
        "oldview" -> "OldView"
        "checkin" -> "签到墙"
        "scratch_card" -> "刮刮乐"
        "report_progress" -> "举报进度"
        "public_court" -> "公开法庭"
        "cip_center" -> "CIP 小程序"
        "discover_settings" -> "发现设置"
        else -> route
    }
}

/** 推导当前实际生效的消息连接方式标签。 */
private fun deriveConnectionLabel(
    mode: MessageReceiver.Mode,
    wsState: WebSocketManager.ConnectionState
): String {
    val proto = when (mode) {
        // 仅 HTTP：不管 WS 状态，走 HTTP
        MessageReceiver.Mode.HTTP_ONLY -> "HTTP"
        // 仅 WebSocket：只靠 WS
        MessageReceiver.Mode.WS_ONLY -> "WebSocket"
        // WS 优先：WS 连接成功用 WS，否则降级 HTTP 轮询
        MessageReceiver.Mode.WS_PRIORITY ->
            if (wsState == WebSocketManager.ConnectionState.CONNECTED) "WebSocket"
            else "HTTP"
    }
    return "正通过 $proto 连接"
}

