package com.oldchat.material.feature.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.oldchat.material.core.notify.AppForeground
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.core.model.GroupMessage
import com.oldchat.material.core.model.MessagePayloadBuilder
import com.oldchat.material.core.model.Mention
import kotlinx.coroutines.launch

/**
 * Group chat screen — message list with sender info + member list access.
 * Mirrors group chat UI from original client §4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    groupName: String,
    onBack: () -> Unit = {},
    onMembersClick: () -> Unit = {},
    onOpenMusic: (String) -> Unit = {},
    groupViewModel: GroupChatViewModel = viewModel()
) {
    val messages by groupViewModel.messages.collectAsStateWithLifecycle()
    val members by groupViewModel.members.collectAsStateWithLifecycle()
    val myAvatarUrl by groupViewModel.myAvatarUrl.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showMemberSheet by remember { mutableStateOf(false) }
    var showRedPacketDialog by remember { mutableStateOf(false) }
    // 附件抽屉（+ 号展开，内含图片/文件/红包）
    var showAttachmentDrawer by remember { mutableStateOf(false) }
    // 我的表情抽屉
    var showEmojiSheet by remember { mutableStateOf(false) }
    // 引用草稿：长按消息选「引用」后进入待发送状态
    var quoteDraft by remember { mutableStateOf<GroupMessage?>(null) }
    val clipboard = LocalClipboardManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { groupViewModel.uploadAndSendImage(context, it) } }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
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
                    groupViewModel.uploadAndSendFile(context, it, fileName, mimeType, fileSize)
                }
            }
        }
    }

    LaunchedEffect(groupId) {
        groupViewModel.init(groupId, groupName)
    }

    // 首次进入：等历史加载稳定后定位到底部（避免 LazyColumn 未布局导致居中/失败）
    var scrollInitDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages) {
        if (!scrollInitDone && messages.isNotEmpty()) {
            withFrameNanos {}
            listState.scrollToItem(messages.size)
            scrollInitDone = true
            groupViewModel.markRead()
        }
    }
    // 后续新增消息（发送/接收）时滚到底部
    LaunchedEffect(messages.size) {
        if (scrollInitDone && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size)
            groupViewModel.markRead()
        }
    }

    LaunchedEffect(Unit) { groupViewModel.markRead() }

    fun send() {
        if (inputText.isBlank()) return
        groupViewModel.sendText(inputText.trim(), quote = quoteDraft)
        inputText = ""
        quoteDraft = null
        keyboardController?.hide()
        scope.launch {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(groupName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${groupViewModel.getMemberCount()} 名成员",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { groupViewModel.refreshLatest() }) {
                        Icon(Icons.Filled.Refresh, "更新消息")
                    }
                    IconButton(onClick = { showMemberSheet = true }) {
                        Icon(Icons.Filled.People, "成员")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
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
                                    Text("回复 ${draft.cachedSenderName ?: draft.fromUid}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        MessagePayloadBuilder.parse(draft.body).text.ifEmpty { draft.body },
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
                            onValueChange = { inputText = it },
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
                        FilledIconButton(onClick = ::send, enabled = inputText.isNotBlank(),
                            modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Send, "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "load_more") {
                if (messages.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { groupViewModel.loadMoreHistory() }) {
                            Text("加载更多", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                val member = members.firstOrNull { it.uid == msg.fromUid }
                GroupMessageBubble(
                    message = msg,
                    isOwn = groupViewModel.isOwnMessage(msg),
                    memberAvatarUrl = member?.avatarUrl,
                    senderName = member?.nickname ?: msg.cachedSenderName ?: msg.fromUid,
                    senderRole = member?.role,
                    myAvatarUrl = myAvatarUrl,
                    onRetry = { groupViewModel.retryMessage(msg) },
                    onClaimRedPacket = { packetId -> groupViewModel.claimRedPacket(packetId) },
                    onCopy = {
                        val text = MessagePayloadBuilder.parse(msg.body).text.ifEmpty { msg.body }
                        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                    },
                    onQuote = { quoteDraft = msg },
                    onOpenMusic = onOpenMusic,
                    modifier = Modifier.animateItem()
                )
            }
            if (messages.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                        Text("群聊暂无消息", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    // Member list bottom sheet
    if (showMemberSheet) {
        ModalBottomSheet(onDismissRequest = { showMemberSheet = false }) {
            MemberListSheet(members = members, onDismiss = { showMemberSheet = false })
        }
    }

    // 附件抽屉：图片 / 文件 / 红包
    if (showAttachmentDrawer) {
        ModalBottomSheet(onDismissRequest = { showAttachmentDrawer = false }) {
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
        ModalBottomSheet(onDismissRequest = { showEmojiSheet = false }) {
            EmojiPickerSheet(
                onPick = { url ->
                    groupViewModel.sendEmoji(url)
                    showEmojiSheet = false
                },
                onDismiss = { showEmojiSheet = false }
            )
        }
    }

    // 发红包对话框
    if (showRedPacketDialog) {
        RedPacketComposerDialog(
            onDismiss = { showRedPacketDialog = false },
            onSend = { amount, count ->
                groupViewModel.sendRedPacket(amount, count)
                showRedPacketDialog = false
            }
        )
    }

    // BUG-09：标记当前正在查看的群会话，避免给自己正在看的会话弹通知
    DisposableEffect(groupId) {
        AppForeground.activeChatId = groupId
        onDispose {
            if (AppForeground.activeChatId == groupId) AppForeground.activeChatId = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { groupViewModel.destroy() }
    }
}

// ---- Group Message Bubble ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    isOwn: Boolean,
    onRetry: () -> Unit,
    memberAvatarUrl: String? = null,
    senderName: String? = null,
    senderRole: String? = null,
    myAvatarUrl: String? = null,
    onClaimRedPacket: (String) -> Unit = {},
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    onOpenMusic: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isOwn) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.secondaryContainer
    // 群聊与单聊完全一致：自己在右（End）、尖角朝右（右下尖）；对方在左（Start）、尖角朝左（左下尖）。
    // 用命名参数明确表达方向，避免位置参数顺序歧义。
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOwn) 16.dp else 4.dp,
        bottomEnd = if (isOwn) 4.dp else 16.dp
    )

    // 长按菜单状态
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
            // 位置不调换：自己在右（End），对方在左（Start）
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            // 对方消息头像（左侧）
            if (!isOwn) {
                GroupAvatar(resolveMemberAvatar(memberAvatarUrl), senderName ?: "")
                Spacer(Modifier.width(8.dp))
            }

            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
            ) {
                // 对方消息顶部显示发送者昵称（用 members 匹配的昵称，不再是 uid）
                if (!isOwn && !senderName.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    ) {
                        Text(senderName, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (senderRole == "owner" || senderRole == "admin") {
                            Spacer(Modifier.width(4.dp))
                            Surface(shape = RoundedCornerShape(3.dp),
                                color = if (senderRole == "owner") Color(0xFFFF9800) else MaterialTheme.colorScheme.tertiary) {
                                Text(if (senderRole == "owner") "群主" else "管理",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    }
                }

                // Status
                if (isOwn && message.isLocalFailed) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Filled.Error, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("发送失败，点击重发", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                // 气泡：形状由 shape 决定尖角方向，内容不镜像，文字/图片始终可读
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
                        GroupMessageContent(message, onOpenMusic, onClaimRedPacket)
                        // 发送时间 + 送达对号（仅自己发送的消息，且服务端返回 delivered/status 时显示）
                        if (message.createdAt > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    formatGroupMessageTime(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                // 群聊送达对号：服务端群聊接口当前不返回 delivered_at，故恒不显示；
                                // 保留此逻辑：一旦 deliveredAt/status 有值即显示单个对号。
                                if (isOwn && !message.isLocalPending && !message.isLocalFailed) {
                                    val delivered = message.deliveredAt > 0 || message.status >= 2
                                    if (delivered) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Filled.Done,
                                            contentDescription = "已送达",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 自己消息头像（右侧）
            if (isOwn) {
                Spacer(Modifier.width(8.dp))
                GroupAvatar(resolveMemberAvatar(myAvatarUrl), "")
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

@Composable
private fun GroupAvatar(avatarUrl: String?, fallbackName: String) {
    val resolved = resolveMemberAvatar(avatarUrl)
    if (resolved != null) {
        AsyncImage(model = resolved, contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape),
            contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
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

@Composable
private fun GroupMessageContent(message: GroupMessage, onOpenMusic: (String) -> Unit = {}, onClaimRedPacket: (String) -> Unit = {}) {
    // 复用单聊完整渲染器（MessageContent），保证单聊/群聊消息类型渲染一致：
    // text / image（全屏预览）/ video / voice / emoji / file / 红包 / forward / music 全部一致。
    val tempMsg = com.oldchat.material.core.model.Message(
        id = message.id, body = message.body, msgType = message.msgType,
        mediaUrl = message.mediaUrl, thumbUrl = message.thumbUrl,
        durationMs = message.durationMs, localProgress = message.localProgress,
        cachedPayload = message.cachedPayload
    )
    MessageContent(tempMsg, onOpenMusic = onOpenMusic, onClaimRedPacket = onClaimRedPacket)
}

@Composable
private fun GroupTextBubble(message: GroupMessage) {
    val payload = message.cachedPayload ?: MessagePayloadBuilder.parse(message.body)
    Column {
        if (payload.quote != null) {
            Surface(Modifier.fillMaxWidth().padding(bottom = 6.dp), shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
                Column(Modifier.padding(8.dp)) {
                    Text(payload.quote.fromName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(payload.quote.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = payload.text.ifEmpty { message.body },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// Simplified placeholders for non-text types (stage 7 full implementation)
@Composable private fun GroupImageBubble(msg: GroupMessage) {
    var showPreview by remember { mutableStateOf(false) }
    val fullUrl = resolveGroupMediaUrl(msg.mediaUrl)
    val thumbUrl = resolveGroupMediaUrl(msg.thumbUrl) ?: fullUrl
    Box(
        modifier = Modifier
            .sizeIn(maxWidth = 240.dp, maxHeight = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (fullUrl != null) showPreview = true }
    ) {
        if (thumbUrl != null) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = "图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Image, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("图片")
            }
        }
    }
    if (showPreview && fullUrl != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showPreview = false }) {
            Box(
                modifier = Modifier.fillMaxSize().clickable { showPreview = false },
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

/** 群聊媒体 URL 相对路径 → 完整 URL（动态跟随登录/文件服务器）。 */
private fun resolveGroupMediaUrl(path: String?): String? {
    return com.oldchat.material.OldChatApplication.instance.serverConfig.resolveMediaUrl(path)
}
@Composable private fun VideoPlaceholder(msg: GroupMessage) {
    Row { Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp)); Text("视频 ${msg.durationMs}ms") }
}
@Composable private fun VoicePlaceholder(msg: GroupMessage) {
    Row { Icon(Icons.Filled.Mic, null, Modifier.size(24.dp)); Text("语音 ") }
}
@Composable private fun FilePlaceholder(msg: GroupMessage) {
    val parts = msg.body.split("|")
    Row { Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(parts.getOrElse(0) { "文件" }) }
}
@Composable private fun RedPacketPlaceholder(msg: GroupMessage, onClaim: (String) -> Unit = {}) {
    val meta = parseGroupRedPacketMeta(msg.body)
    Surface(
        modifier = Modifier
            .width(220.dp)
            .clickable(enabled = meta.packetId.isNotEmpty()) { onClaim(meta.packetId) },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFE53935)
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🧧", fontSize = 36.sp)
            Spacer(Modifier.height(6.dp))
            Text(meta.text.ifEmpty { "恭喜发财" }, color = Color.White,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.totalAmount > 0 || meta.totalCount > 0) {
                Spacer(Modifier.height(4.dp))
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
            Spacer(Modifier.height(6.dp))
            Text("点击领取红包", color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 群聊红包元数据。 */
private data class GroupRedPacketMeta(
    val packetId: String = "",
    val text: String = "",
    val totalAmount: Int = 0,
    val totalCount: Int = 0
)

/** 解析群聊红包 body（JSON：{packet_id,text,total_amount,total_count,v}，兼容旧竖线格式）。 */
private fun parseGroupRedPacketMeta(body: String): GroupRedPacketMeta {
    if (body.isBlank()) return GroupRedPacketMeta()
    if (body.trimStart().startsWith("{")) {
        return try {
            val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
            GroupRedPacketMeta(
                packetId = obj.get("packet_id")?.asString ?: "",
                text = obj.get("text")?.asString ?: "",
                totalAmount = obj.get("total_amount")?.asInt ?: 0,
                totalCount = obj.get("total_count")?.asInt ?: 0
            )
        } catch (_: Exception) { GroupRedPacketMeta() }
    }
    val parts = body.split("|")
    return GroupRedPacketMeta(
        packetId = parts.getOrElse(0) { "" },
        text = parts.getOrElse(1) { "恭喜发财" }
    )
}

// ---- Member List Sheet (§5) ----

@Composable
private fun MemberListSheet(
    members: List<GroupMember>,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxHeight(0.6f)) {
        Text("群成员 (${members.size})", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp))
        if (members.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无成员数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(members, key = { it.uid }) { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (member.avatarUrl != null) {
                            AsyncImage(model = resolveMemberAvatar(member.avatarUrl), contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop)
                        } else {
                            Surface(Modifier.size(40.dp), CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(member.nickname.take(1), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(member.nickname, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (member.role == "owner" || member.role == "admin") {
                            Surface(shape = RoundedCornerShape(4.dp),
                                color = if (member.role == "owner") Color(0xFFFF9800) else MaterialTheme.colorScheme.tertiary) {
                                Text(if (member.role == "owner") "群主" else "管理",
                                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 成员头像相对路径 → 完整 URL（动态跟随登录/文件服务器）。
 */
private fun resolveMemberAvatar(path: String?): String? {
    return com.oldchat.material.OldChatApplication.instance.serverConfig.resolveMediaUrl(path)
}

/**
 * 群聊消息发送时间：同一天显示 HH:mm，跨天显示 yyyy-MM-dd HH:mm。
 */
private fun formatGroupMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = System.currentTimeMillis() / 1000
    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp * 1000))
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(now * 1000))
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp * 1000))
    return if (date == today) time else "$date $time"
}
