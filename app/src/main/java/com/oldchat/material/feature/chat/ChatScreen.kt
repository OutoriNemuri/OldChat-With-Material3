package com.oldchat.material.feature.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oldchat.material.core.notify.AppForeground
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.core.model.Message
import com.oldchat.material.core.model.MessagePayloadBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Direct chat screen — message list + input bar.
 * Mirrors ChatActivity from original client §3.5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    friendUid: String,
    friendName: String,
    onBack: () -> Unit = {},
    onOpenMusic: (String) -> Unit = {},
    friendAvatarUrl: String? = null,
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val myAvatarUrl by chatViewModel.myAvatarUrl.collectAsStateWithLifecycle()
    val isPeerTyping by chatViewModel.isPeerTyping.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showRedPacketDialog by remember { mutableStateOf(false) }
    // 附件抽屉（+ 号展开，内含图片/文件/红包）
    var showAttachmentDrawer by remember { mutableStateOf(false) }
    // 我的表情抽屉
    var showEmojiSheet by remember { mutableStateOf(false) }
    // 引用草稿：长按消息选「引用」后进入待发送状态
    var quoteDraft by remember { mutableStateOf<Message?>(null) }
    val clipboard = LocalClipboardManager.current

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { chatViewModel.uploadAndSendImage(context, it) }
    }

    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val cursor = context.contentResolver.query(it, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    val fileName = if (nameIdx >= 0) c.getString(nameIdx) else "file"
                    val fileSize = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                    chatViewModel.uploadAndSendFile(context, it, fileName, mimeType, fileSize)
                }
            }
        }
    }

    LaunchedEffect(friendUid) {
        chatViewModel.init(friendUid, friendName)
    }

    fun markAsRead() { chatViewModel.markRead() }

    fun sendMessage() {
        if (inputText.isBlank()) return
        chatViewModel.sendText(inputText.trim(), quoteDraft)
        inputText = ""
        quoteDraft = null
        keyboardController?.hide()
        scope.launch {
            // LazyColumn 前置了 item(key="load_more")（占据 index 0），
            // 最后一条消息的真实 index 是 messages.size，而非 messages.size - 1。
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }
    }

    // 首次进入：等历史加载稳定后定位到底部（避免 LazyColumn 未布局导致居中/失败）
    var scrollInitDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages) {
        if (!scrollInitDone && messages.isNotEmpty()) {
            // 等一帧，确保 LazyColumn 已完成布局，再跳到末尾
            withFrameNanos {}
            listState.scrollToItem(messages.size)
            scrollInitDone = true
        }
    }
    // BUG-05：新增消息时不再「无条件」把视图拽到底部——
    // 原来只要有人发消息，正在翻历史的用户就会被强制弹回底部。
    // 现在的规则：用户本来就在底部附近、或这条消息是自己发的，才自动滚到底；
    // 否则只累计「新消息」计数，显示浮标由用户决定何时跳。
    var pendingNewMessages by remember { mutableStateOf(0) }
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(messages.size) {
        if (!scrollInitDone || messages.isEmpty()) return@LaunchedEffect
        val newestIsMine = messages.lastOrNull()?.let { chatViewModel.isOwnMessage(it) } == true
        if (isNearBottom || newestIsMine) {
            listState.scrollToItem(messages.size)
            pendingNewMessages = 0
        } else {
            pendingNewMessages += 1
        }
    }

    // Mark as read on enter
    LaunchedEffect(Unit) { markAsRead() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // ALIGN-15：对方输入中时把标题换成提示
                    if (isPeerTyping) {
                        Text("正在输入…", maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(friendName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { chatViewModel.refreshLatest() }) {
                        Icon(Icons.Filled.Refresh, "更新消息")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Input bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    // 引用预览条
                    val draft = quoteDraft
                    if (draft != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text("回复 ${friendName}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        MessagePayloadBuilder.extractDisplayText(draft).ifEmpty { "[图片/文件]" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { quoteDraft = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, "取消引用", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // + / × 按钮：切换附件抽屉
                        IconButton(
                            onClick = { showAttachmentDrawer = !showAttachmentDrawer },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                if (showAttachmentDrawer) Icons.Filled.Close else Icons.Filled.Add,
                                contentDescription = if (showAttachmentDrawer) "收起" else "更多"
                            )
                        }
                        // 表情按钮：打开「我的表情」抽屉
                        IconButton(
                            onClick = { showEmojiSheet = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Filled.EmojiEmotions, "我的表情")
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                // ALIGN-15：把「正在输入」同步给对方（内部有节流）
                                chatViewModel.onInputChanged(it)
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("输入消息…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = ::sendMessage,
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Filled.Send, "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Load more on scroll to top
            item(key = "load_more") {
                if (messages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { chatViewModel.loadHistory() }) {
                            Text("加载更多", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    isOwn = chatViewModel.isOwnMessage(message),
                    friendAvatarUrl = friendAvatarUrl,
                    myAvatarUrl = myAvatarUrl,
                    onRetry = { chatViewModel.retryMessage(message) },
                    onOpenMusic = onOpenMusic,
                    onClaimRedPacket = { packetId -> chatViewModel.claimRedPacket(packetId) },
                    onCopy = {
                        val text = MessagePayloadBuilder.extractDisplayText(message)
                        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                    },
                    onQuote = { quoteDraft = message },
                    modifier = Modifier.animateItem()
                    onBurnOpen = { chatViewModel.openBurnMessage(it) },
                )
            }

            // Empty state
            if (messages.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("开始聊天吧", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

            // BUG-05：不在底部时，新消息只用浮标提示（用户自己决定何时跳回去）
            if (pendingNewMessages > 0) {
                Surface(
                    onClick = {
                        scope.launch {
                            listState.scrollToItem(messages.size)
                            pendingNewMessages = 0
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, "回到最新",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$pendingNewMessages 条新消息",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }

    if (showRedPacketDialog) {
        RedPacketComposerDialog(
            onDismiss = { showRedPacketDialog = false },
            onSend = { amount, count ->
                chatViewModel.sendRedPacket(amount, count)
                showRedPacketDialog = false
            }
        )
    }

    // 附件抽屉：图片 / 文件 / 红包
    if (showAttachmentDrawer) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentDrawer = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("发送", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 图片
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                showAttachmentDrawer = false
                                imagePickerLauncher.launch("image/*")
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Filled.Image, "图片")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("图片", style = MaterialTheme.typography.labelMedium)
                    }
                    // 文件
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                showAttachmentDrawer = false
                                filePickerLauncher.launch("*/*")
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Filled.AttachFile, "文件")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("文件", style = MaterialTheme.typography.labelMedium)
                    }
                    // 红包
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                showAttachmentDrawer = false
                                showRedPacketDialog = true
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Text("🧧", fontSize = 24.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("红包", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 我的表情抽屉
    if (showEmojiSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiSheet = false }
        ) {
            EmojiPickerSheet(
                onPick = { url ->
                    chatViewModel.sendEmoji(url)
                    showEmojiSheet = false
                },
                onDismiss = { showEmojiSheet = false }
            )
        }
    }

    // BUG-09：记录「当前正在看的会话」，避免给正在看的会话再弹通知
    DisposableEffect(friendUid) {
        AppForeground.activeChatId = friendUid
        onDispose {
            if (AppForeground.activeChatId == friendUid) AppForeground.activeChatId = null
        }
    }

    // ALIGN-02：回到前台补一次回执刷新（配合事件驱动，不再 5 秒轮询）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> chatViewModel.onScreenResumed()
                // ALIGN-14：后台停掉前台专用的刷新型工作
                Lifecycle.Event.ON_PAUSE -> chatViewModel.onScreenPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ALIGN-13：向上滚到顶时按需拉取更早一页（进入会话只拉最新页）
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                // 内部已有 isLoadingMore / historyHasMore 守卫，这里只做触发
                if (firstVisible <= 2) chatViewModel.loadMoreHistory()
            }
    }

    DisposableEffect(Unit) {
        onDispose { chatViewModel.destroy() }
    }
}

// ---- Message Bubble ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    onRetry: () -> Unit,
    onOpenMusic: (String) -> Unit = {},
    friendAvatarUrl: String? = null,
    myAvatarUrl: String? = null,
    onClaimRedPacket: (String) -> Unit = {},
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    // ALIGN-17：阅后即焚「已查看」上报
    onBurnOpen: (Message) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isOwn)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer

    // 三圆角矩形：尖角朝向对方。自己在右（尖角朝右），对方在左（尖角朝左）。
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOwn) 16.dp else 4.dp,
        bottomEnd = if (isOwn) 4.dp else 16.dp
    )

    val avatar = if (!isOwn) resolveAvatarForBubble(friendAvatarUrl) else null
    val selfAvatar = resolveAvatarForBubble(myAvatarUrl)

    // 长按菜单状态
    var menuExpanded by remember { mutableStateOf(false) }

    // BUG-21 / ALIGN-17：阅后即焚。
    // 原实现只把 burn_after_seconds 解析进模型，UI 完全无视 → 「阅后即焚」形同虚设。
    // 现在：点击查看一次 → 倒计时 burnAfterSeconds → 到期销毁正文（本地不再展示）。
    // 注意：服务端销毁回执端点未在官方文档中给出，这里只做本地销毁，不伪造服务端行为。
    if (message.burnAfterSeconds > 0) {
        BurnMessageBubble(
            message = message,
            isOwn = isOwn,
            modifier = modifier,
            onOpen = { onBurnOpen(message) }
        )
        return
    }

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            // 对方头像（左侧）
            if (!isOwn) {
                AvatarBox(avatar = avatar, fallbackName = "", size = 36)
                Spacer(Modifier.width(8.dp))
            }

            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
            ) {
                // Status indicator
                if (isOwn && message.status == Message.STATUS_SENT) {
                    Text("已发送", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp))
                }
                if (isOwn && message.isLocalFailed) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Filled.Error, null, modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("发送失败，点击重发", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }

                // 气泡：形状由 shape 决定尖角方向，内容不镜像翻转，文字/图片始终可读
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(shape)
                        .background(bubbleColor)
                        .combinedClickable(
                            onClick = { menuExpanded = false },
                            onLongClick = { menuExpanded = true }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column(horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start) {
                        MessageContent(message, onOpenMusic, onClaimRedPacket)
                        // 发送时间 + 送达/已读对号（仅自己发送的消息显示）
                        if (message.createdAt > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    formatMessageTime(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                // 单聊对号：delivered → 1 个对号；read → 2 个对号
                                ReadReceiptTicks(isOwn, message)
                            }
                        }
                    }
                }
            }

            // 自己头像（右侧）
            if (isOwn) {
                Spacer(Modifier.width(8.dp))
                AvatarBox(avatar = selfAvatar, fallbackName = "", size = 36)
            }
        }

        // 长按弹出菜单：复制 / 引用
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            DropdownMenuItem(
                text = { Text("复制", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(20.dp)) },
                onClick = {
                    menuExpanded = false
                    onCopy()
                }
            )
            DropdownMenuItem(
                text = { Text("引用", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Filled.FormatQuote, null, modifier = Modifier.size(20.dp)) },
                onClick = {
                    menuExpanded = false
                    onQuote()
                }
            )
        }
    }
}

/**
 * 送达/已读对号：单聊自己消息在发送时间旁显示。
 * - delivered（deliveredAt > 0）：显示 1 个对号
 * - read（readAt > 0）：显示 2 个对号
 * 仅自己发送的消息（isOwn=true）才显示；本地待发送（isLocalPending）不显示。
 */
@Composable
internal fun ReadReceiptTicks(isOwn: Boolean, message: Message) {
    if (!isOwn || message.isLocalPending || message.isLocalFailed) return
    val delivered = message.deliveredAt > 0 || message.status >= Message.STATUS_DELIVERED
    val read = message.readAt > 0 || message.status >= Message.STATUS_READ
    if (!delivered && !read) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(4.dp))
        // 双对号（已读）用两个重叠的 Done 图标；单对号（送达）用单个 Done
        if (read) {
            Icon(
                Icons.Filled.Done,
                contentDescription = "已读",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Icon(
                Icons.Filled.Done,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp).offset(x = (-6).dp)
            )
        } else {
            Icon(
                Icons.Filled.Done,
                contentDescription = "已送达",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 气泡头像：有 URL 显示图片，否则显示灰色圆形占位。
 */
@Composable
private fun AvatarBox(avatar: String?, fallbackName: String, size: Int) {
    if (!avatar.isNullOrBlank()) {
        AsyncImage(
            model = avatar,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (fallbackName.isNotEmpty()) {
                Text(fallbackName.take(1), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 头像相对路径 → 完整 URL（动态跟随登录/文件服务器）。 */
private fun resolveAvatarForBubble(avatarUrl: String?): String? {
    return com.oldchat.material.OldChatApplication.instance.serverConfig.resolveMediaUrl(avatarUrl)
}

// ---- Message Content Renderers ----

@Composable
internal fun MessageContent(message: Message, onOpenMusic: (String) -> Unit = {}, onClaimRedPacket: (String) -> Unit = {}) {
    // 音乐消息：body 的 media_kind == "music"
    val payload = message.cachedPayload ?: MessagePayloadBuilder.parse(message.body)
    if (payload.mediaKind == "music") {
        MusicBubble(message, onOpenMusic)
        return
    }
    when (message.msgType) {
        "text" -> TextBubble(message)
        "image" -> ImageBubble(message)
        "video" -> VideoBubble(message)
        "voice" -> VoiceBubble(message)
        "emoji" -> EmojiBubble(message)
        "resource" -> FileBubble(message)
        "red_packet" -> RedPacketBubble(message, onClaimRedPacket)
        "forward" -> ForwardBubble(message)
        else -> TextBubble(message)
    }
}

@Composable
private fun MusicBubble(message: Message, onOpenMusic: (String) -> Unit = {}) {
    val payload = message.cachedPayload ?: MessagePayloadBuilder.parse(message.body)
    val meta = parseMusicMeta(payload.text)

    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable {
                val title = meta.title
                if (title.isNotEmpty()) onOpenMusic(title)
            }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                meta.title.ifEmpty { "音乐" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (meta.artist.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                meta.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "点击播放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 音乐消息元数据。
 */
private data class MusicMeta(
    val title: String = "",
    val artist: String = "",
    val songId: String = ""
)

/**
 * 从音乐消息的 text 里解析歌名、歌手、歌曲ID。
 * text 格式（人可读，字段间以换行分隔）：
 *   歌曲: Come Back To Me
 *   歌手: qwer1026
 *   时长: 03:57
 *   封面: ...
 *   歌曲ID: 5mB-peal1FX2mdKD
 *   歌词: ...
 *   点击播放
 */
private fun parseMusicMeta(text: String): MusicMeta {
    if (text.isBlank()) return MusicMeta()
    val lines = text.lineSequence().map { it.trim() }.toList()
    var title = ""
    var artist = ""
    var songId = ""

    for (i in lines.indices) {
        val line = lines[i]
        when {
            line.startsWith("歌曲:") || line.startsWith("歌曲：") -> {
                title = line.substringAfter(":").substringAfter("：").trim()
                // 歌名可能跨行（下一行不是已知字段时拼接）
                if (title.isEmpty() || (i + 1 < lines.size && !isKnownField(lines[i + 1]))) {
                    var j = i + 1
                    while (j < lines.size && !isKnownField(lines[j])) {
                        title = (title + " " + lines[j]).trim()
                        j++
                    }
                }
            }
            line.startsWith("歌手:") || line.startsWith("歌手：") -> {
                artist = line.substringAfter(":").substringAfter("：").trim()
            }
            line.startsWith("歌曲ID:") || line.startsWith("歌曲ID：") ||
                line.startsWith("歌曲id:") || line.startsWith("歌曲ID ") -> {
                songId = line.substringAfter(":").substringAfter("：").trim()
            }
        }
    }
    return MusicMeta(title = title, artist = artist, songId = songId)
}

private fun isKnownField(line: String): Boolean {
    return line.startsWith("歌手") || line.startsWith("时长") || line.startsWith("封面") ||
        line.startsWith("歌曲ID") || line.startsWith("歌曲id") || line.startsWith("歌词") ||
        line.startsWith("点击")
}

@Composable
private fun TextBubble(message: Message) {
    val text = MessagePayloadBuilder.extractDisplayText(message)
    // Parse quote
    val payload = message.cachedPayload ?: MessagePayloadBuilder.parse(message.body)
    Column {
        // Quote block
        if (payload.quote != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(payload.quote.fromName, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                    Text(payload.quote.text, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Mentions highlighting (simplified — show mentions with @ prefix color)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        // Mentions visually different
        payload.mentions.forEach { mention ->
            // For simplicity, mentions are rendered as part of the text;
            // full inline styling would require AnnotatedString
        }
    }
}

@Composable
private fun ImageBubble(message: Message) {
    var showPreview by remember { mutableStateOf(false) }
    // full 图优先用 media_url；thumb 缺省时 fallback 到 full（群聊/部分图片无 thumb_url）
    val fullUrl = resolveMediaUrl(message.mediaUrl)
    val thumbUrl = resolveMediaUrl(message.thumbUrl) ?: fullUrl

    Column {
        if (thumbUrl != null) {
            Box(
                modifier = Modifier
                    .sizeIn(maxWidth = 240.dp, maxHeight = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { if (fullUrl != null) showPreview = true }
            ) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = "图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (message.localProgress in 0..99) {
                    LinearProgressIndicator(
                        progress = { message.localProgress / 100f },
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Image, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("图片", style = MaterialTheme.typography.bodyLarge)
            }
        }
        // Caption text
        val text = MessagePayloadBuilder.extractDisplayText(message)
        if (text.isNotEmpty() && text != "[图片]") {
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp))
        }
    }

    // 全屏预览
    if (showPreview && fullUrl != null) {
        Dialog(onDismissRequest = { showPreview = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showPreview = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = fullUrl,
                    contentDescription = "图片预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/**
 * 媒体 URL 相对路径 → 完整 URL（动态跟随登录/文件服务器）。
 */
private fun resolveMediaUrl(path: String?): String? {
    return com.oldchat.material.OldChatApplication.instance.serverConfig.resolveMediaUrl(path)
}

@Composable
private fun VideoBubble(message: Message) {
    Column {
        Box(
            modifier = Modifier
                .size(width = 240.dp, height = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (message.thumbUrl != null) {
                AsyncImage(
                    model = message.thumbUrl,
                    contentDescription = "视频",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // Play button overlay
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, "播放",
                        tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (message.durationMs > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        formatDuration(message.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceBubble(message: Message) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { /* TODO: Play voice */ }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(24.dp))
        }
        // Waveform placeholder
        Box(
            modifier = Modifier
                .width((message.durationMs / 100).coerceIn(20, 120).dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(message.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmojiBubble(message: Message) {
    if (message.thumbUrl != null) {
        AsyncImage(
            model = message.thumbUrl,
            contentDescription = "表情",
            modifier = Modifier.size(120.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Text("🎨", fontSize = 48.sp)
    }
}

@Composable
private fun FileBubble(message: Message) {
    val parts = message.body.split("|")
    val fileName = parts.getOrElse(0) { "文件" }
    val fileSize = parts.getOrElse(1) { "" }.toLongOrNull() ?: 0L
    val downloadUrl = parts.getOrElse(2) { "" }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.InsertDriveFile, null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(fileName, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp))
            Text(formatFileSize(fileSize), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { /* TODO: Download file */ },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Filled.Download, "下载", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RedPacketBubble(message: Message, onClaim: (String) -> Unit = {}) {
    // 红包 body 为 JSON：{"packet_id":"...","text":"红包名","total_amount":106,"total_count":1,"v":1}
    val meta = parseRedPacketMeta(message.body)

    Surface(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFE53935)
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = meta.packetId.isNotEmpty()) { onClaim(meta.packetId) }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🧧", fontSize = 36.sp)
            Spacer(Modifier.height(8.dp))
            // 红包名（text）
            Text(
                meta.text.ifEmpty { "恭喜发财" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 数额 + 数量
            Spacer(Modifier.height(4.dp))
            if (meta.totalAmount > 0 || meta.totalCount > 0) {
                Text(
                    buildString {
                        if (meta.totalCount > 0) append("${meta.totalCount} 个")
                        if (meta.totalAmount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${meta.totalAmount} 旧币")
                        }
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("点击领取红包", color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 红包消息元数据。
 */
private data class RedPacketMeta(
    val packetId: String = "",
    val text: String = "",
    val totalAmount: Int = 0,
    val totalCount: Int = 0
)

/**
 * 解析红包 body（JSON 格式）。
 * 兼容两种：JSON {packet_id, text, total_amount, total_count, v} 和旧的 "id|greeting" 竖线格式。
 */
private fun parseRedPacketMeta(body: String): RedPacketMeta {
    if (body.isBlank()) return RedPacketMeta()
    // 尝试 JSON
    if (body.trimStart().startsWith("{")) {
        return try {
            val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
            RedPacketMeta(
                packetId = obj.get("packet_id")?.asString ?: "",
                text = obj.get("text")?.asString ?: "",
                totalAmount = obj.get("total_amount")?.asInt ?: 0,
                totalCount = obj.get("total_count")?.asInt ?: 0
            )
        } catch (_: Exception) {
            RedPacketMeta()
        }
    }
    // 旧格式 "id|greeting"
    val parts = body.split("|")
    return RedPacketMeta(
        packetId = parts.getOrElse(0) { "" },
        text = parts.getOrElse(1) { "恭喜发财" }
    )
}

@Composable
private fun ForwardBubble(message: Message) {
    val payload = message.cachedPayload ?: MessagePayloadBuilder.parse(message.body)
    val title = payload.forwardV2?.title ?: "聊天记录"

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            if (payload.forwardV2 != null) {
                payload.forwardV2.items.take(3).forEach { item ->
                    Text(
                        "${item.fromName}: ${item.text}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (payload.forwardV2.items.size > 3) {
                    Text("… 等 ${payload.forwardV2.items.size} 条消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- 发红包对话框（单聊/群聊共用） ----

/**
 * 发红包对话框：输入总金额（旧币）+ 红包个数。
 * total_amount = 总金额（旧币），total_count = 红包个数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RedPacketComposerDialog(
    onDismiss: () -> Unit,
    onSend: (totalAmount: Int, totalCount: Int) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("1") }

    val amount = amountText.toIntOrNull() ?: 0
    val count = countText.toIntOrNull() ?: 0
    val valid = amount > 0 && count > 0 && amount >= count

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发红包") },
        text = {
            Column {
                Text("红包总金额（旧币）", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如 10") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text("红包个数", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如 3") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                if (!valid && (amount > 0 || count > 0)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "总金额需 ≥ 红包个数（每个至少 1 旧币）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(amount, count) },
                enabled = valid
            ) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---- Helpers ----


private fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = System.currentTimeMillis() / 1000
    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp * 1000))
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(now * 1000))
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp * 1000))
    // 同一天只显示时分，否则显示日期+时分
    return if (date == today) time else "$date $time"
}

private fun formatDuration(ms: Int): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) "$minutes'${remainingSeconds.toString().padStart(2, '0')}\""
    else "$seconds\""
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}


/**
 * ALIGN-17 / BUG-21：阅后即焚气泡。
 *
 * 状态机：locked（未读）→ counting（已展开，倒计时中）→ burned（已销毁）。
 * 销毁只作用于本地展示与内存列表，不伪装「服务端已删除」——
 * 官方文档未定义销毁回执端点，客户端不应该假装做过。
 */
@Composable
private fun BurnMessageBubble(
    message: Message,
    isOwn: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {}
) {
    var revealed by remember(message.id) { mutableStateOf(false) }
    var remaining by remember(message.id) { mutableStateOf(message.burnAfterSeconds) }
    var burned by remember(message.id) { mutableStateOf(false) }

    LaunchedEffect(revealed, remaining) {
        if (!revealed || remaining <= 0) return@LaunchedEffect
        delay(1000L)
        remaining -= 1
        if (remaining <= 0) burned = true
    }

    val content = MessagePayloadBuilder.extractPreviewText(message.msgType, message.body)

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable(enabled = !revealed && !burned) {
                revealed = true
                // ALIGN-17：查看即上报（只对对方发来的消息上报，自己发的不需要）
                if (!isOwn) onOpen()
            }
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocalFireDepartment, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            burned -> "已焚毁"
                            revealed -> "阅后即焚 · ${remaining}s"
                            else -> "阅后即焚消息"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (revealed && !burned) {
                    Spacer(Modifier.height(6.dp))
                    Text(content.ifBlank { "[非文本消息]" }, style = MaterialTheme.typography.bodyMedium)
                } else if (!revealed) {
                    Spacer(Modifier.height(6.dp))
                    Text("点击查看", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
