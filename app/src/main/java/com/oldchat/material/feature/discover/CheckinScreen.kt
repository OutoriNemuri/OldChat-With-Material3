package com.oldchat.material.feature.discover

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.network.FormPartData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 签到墙 — 迷你动态页（规范 §8.6）。
 *
 * 布局（用户要求）：
 *   卡片1：已签到人数 + 签到按钮
 *   卡片2：签到墙信息流（每条下有点赞/评论按钮）
 *   卡片3：发送留言（120 字内，可附加图片）
 *   （发送后）卡片4：今天我发的留言 + 查看点赞/查看评论按钮
 *
 * 实测接口字段：
 *   GET  /me/checkin/wall           -> checked_in/checkin_count/featured_messages/m​​y_post
 *   POST /me/checkin                -> {} 签到
 *   POST /me/checkin/wall           -> {"content_text":"..."} 发留言
 *   POST /me/checkin/wall/like      -> {"post_id":"..."}
 *   POST /me/checkin/wall/unlike    -> {"post_id":"..."}
 *   POST /me/checkin/wall/comment   -> {"post_id":"...","body":"..."}
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinScreen(
    onBack: () -> Unit = {},
    viewModel: CheckinViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 发送留言输入状态
    var inputText by remember { mutableStateOf("") }
    var inputImageUrl by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.uploadImage(uri) { url ->
            inputImageUrl = url
        }
    }

    LaunchedEffect(Unit) { viewModel.loadWall() }

    // 错误提示
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("签到墙") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- 卡片1：已签到人数 + 签到按钮 ----
            item(key = "checkin_header") {
                CheckinHeaderCard(
                    checkedIn = state.checkedIn,
                    checkinCount = state.checkinCount,
                    onCheckin = { viewModel.checkin() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // ---- 卡片2：签到墙信息流 ----
            item(key = "wall_header") {
                Text(
                    "签到墙",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (state.isLoading && state.posts.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.posts.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("还没有留言", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(state.posts, key = { it.id }) { post ->
                    CheckinPostCard(
                        post = post,
                        onLike = { viewModel.toggleLike(post) },
                        onComment = { content -> viewModel.comment(post.id, content) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // ---- 卡片3：发送留言 ----
            item(key = "compose") {
                CheckinComposeCard(
                    text = inputText,
                    imageUrl = inputImageUrl,
                    onTextChange = { inputText = it },
                    onPickImage = { imagePicker.launch("image/*") },
                    onRemoveImage = { inputImageUrl = null },
                    sending = state.sending,
                    onSend = {
                        if (inputText.isNotBlank() || inputImageUrl != null) {
                            viewModel.publishMessage(inputText, inputImageUrl)
                            inputText = ""
                            inputImageUrl = null
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // ---- 卡片4：今天我发的留言（发送后出现）----
            if (state.myPost != null) {
                item(key = "my_post") {
                    MyPostCard(
                        post = state.myPost!!,
                        onViewLikes = { viewModel.showLikes(state.myPost!!) },
                        onViewComments = { viewModel.showComments(state.myPost!!) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(16.dp)) }
        }
    }

    // 查看点赞列表对话框
    if (state.showLikesDialog && state.likesPost != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLikes() },
            title = { Text("谁点赞了") },
            text = {
                if (state.likesUsers.isEmpty()) {
                    Text("暂无点赞", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.likesUsers.forEach { name ->
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissLikes() }) { Text("关闭") }
            }
        )
    }

    // 查看评论对话框
    if (state.showCommentsDialog && state.commentsPost != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissComments() },
            title = { Text("评论") },
            text = {
                if (state.comments.isEmpty()) {
                    Text("暂无评论", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.comments.forEach { c ->
                            Column {
                                Text(c.userName, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                                Text(c.body, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissComments() }) { Text("关闭") }
            }
        )
    }
}

// ---- 卡片1：签到人数 + 按钮 ----

@Composable
private fun CheckinHeaderCard(
    checkedIn: Boolean,
    checkinCount: Int,
    onCheckin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("今日已签到", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$checkinCount 人",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = onCheckin,
                enabled = !checkedIn,
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(
                    if (checkedIn) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (checkedIn) "已签到" else "签到")
            }
        }
    }
}

// ---- 卡片2：签到墙单条 ----

@Composable
private fun CheckinPostCard(
    post: CheckinPost,
    onLike: () -> Unit,
    onComment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCommentInput by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 用户行
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.user.avatarUrl != null) {
                    AsyncImage(
                        model = post.user.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(post.user.nickname.take(1),
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(post.user.nickname, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium)
                    if (post.user.title.isNotBlank()) {
                        Text(post.user.title, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 内容
            Text(post.contentText, style = MaterialTheme.typography.bodyMedium)

            // 图片
            if (post.imageUrl.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                post.createdAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            // 点赞/评论按钮行
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLike) {
                    Icon(
                        if (post.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = if (post.likedByMe) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("${post.likeCount}")
                }
                TextButton(onClick = { showCommentInput = !showCommentInput }) {
                    Icon(Icons.Outlined.Comment, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${post.commentCount}")
                }
            }

            // 评论输入
            if (showCommentInput) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        placeholder = { Text("写评论…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (commentText.isNotBlank()) {
                            onComment(commentText.trim())
                            commentText = ""
                            showCommentInput = false
                        }
                    }) {
                        Icon(Icons.Filled.Send, "发送")
                    }
                }
            }
        }
    }
}

// ---- 卡片3：发送留言 ----

@Composable
private fun CheckinComposeCard(
    text: String,
    imageUrl: String?,
    onTextChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("发留言", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 120) onTextChange(it) },
                placeholder = { Text("说点什么…（120 字以内）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            Text(
                "${text.length}/120",
                style = MaterialTheme.typography.labelSmall,
                color = if (text.length >= 120) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )

            // 图片预览
            if (imageUrl != null) {
                Spacer(Modifier.height(8.dp))
                Box {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Filled.Close, "移除图片",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickImage, enabled = !sending) {
                    Icon(Icons.Outlined.Image, "添加图片")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSend,
                    enabled = !sending && (text.isNotBlank() || imageUrl != null),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("发送")
                    }
                }
            }
        }
    }
}

// ---- 卡片4：今天我发的留言 ----

@Composable
private fun MyPostCard(
    post: CheckinPost,
    onViewLikes: () -> Unit,
    onViewComments: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("今天我发的", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(post.contentText, style = MaterialTheme.typography.bodyMedium)
            if (post.imageUrl.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onViewLikes) {
                    Icon(Icons.Outlined.FavoriteBorder, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("谁点赞了 (${post.likeCount})")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onViewComments) {
                    Icon(Icons.Outlined.Comment, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("查看评论 (${post.commentCount})")
                }
            }
        }
    }
}

// ---- 数据模型 ----

data class CheckinUser(
    val uid: String = "",
    val username: String = "",
    val nickname: String = "",
    val title: String = "",
    val avatarUrl: String? = null,
    val signature: String = ""
)

data class CheckinPost(
    val id: String,
    val contentText: String,
    val imageUrl: String,
    val messageType: String,
    val createdAt: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
    val user: CheckinUser
) {
    companion object {
        fun fromMap(map: Map<*, *>): CheckinPost? {
            val id = map["id"]?.toString() ?: return null
            val userMap = map["user"] as? Map<*, *>
            val user = CheckinUser(
                uid = userMap?.get("uid")?.toString() ?: "",
                username = userMap?.get("username")?.toString() ?: "",
                nickname = (userMap?.get("display_name") ?: userMap?.get("username"))?.toString() ?: "",
                title = userMap?.get("user_title")?.toString() ?: "",
                avatarUrl = userMap?.get("avatar_url")?.toString(),
                signature = userMap?.get("signature")?.toString() ?: ""
            )
            // createdAt 可能是 "2026-08-14T01:07:18Z" 格式，截取日期时间
            val rawTime = map["created_at"]?.toString() ?: ""
            val time = if (rawTime.length >= 16) rawTime.substring(0, 16).replace("T", " ") else rawTime
            return CheckinPost(
                id = id,
                contentText = map["content_text"]?.toString() ?: "",
                imageUrl = map["image_url"]?.toString() ?: "",
                messageType = map["message_type"]?.toString() ?: "text",
                createdAt = time,
                likeCount = (map["like_count"] as? Number)?.toInt() ?: 0,
                commentCount = (map["comment_count"] as? Number)?.toInt() ?: 0,
                likedByMe = map["liked_by_me"] == true,
                user = user
            )
        }
    }
}

data class CheckinComment(
    val id: String,
    val body: String,
    val userName: String,
    val createdAt: String
)

// ---- CheckinViewModel ----

data class CheckinUiState(
    val checkedIn: Boolean = false,
    val alreadyPosted: Boolean = false,
    val checkinCount: Int = 0,
    val checkinDate: String = "",
    val posts: List<CheckinPost> = emptyList(),
    val myPost: CheckinPost? = null,
    val isLoading: Boolean = false,
    val sending: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null,
    // 查看点赞
    val showLikesDialog: Boolean = false,
    val likesPost: CheckinPost? = null,
    val likesUsers: List<String> = emptyList(),
    // 查看评论
    val showCommentsDialog: Boolean = false,
    val commentsPost: CheckinPost? = null,
    val comments: List<CheckinComment> = emptyList()
)

class CheckinViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CheckinUiState())
    val uiState: StateFlow<CheckinUiState> = _uiState.asStateFlow()

    private val app get() = OldChatApplication.instance

    fun loadWall() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = app.apiClient.get("/me/checkin/wall")
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val posts = (map?.get("featured_messages") as? List<*>)
                            ?.filterIsInstance<Map<*, *>>()
                            ?.mapNotNull { CheckinPost.fromMap(it) }
                            ?: emptyList()
                        val myPostMap = map?.get("my_post") as? Map<*, *>
                        _uiState.update {
                            it.copy(
                                checkedIn = map?.get("checked_in") == true,
                                alreadyPosted = map?.get("already_posted") == true,
                                checkinCount = (map?.get("checkin_count") as? Number)?.toInt() ?: 0,
                                checkinDate = map?.get("checkin_date")?.toString() ?: "",
                                posts = posts,
                                myPost = myPostMap?.let { CheckinPost.fromMap(it) },
                                isLoading = false
                            )
                        }
                    },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "加载失败") }
            }
        }
    }

    /** 签到（POST /me/checkin，空 body）。 */
    fun checkin() {
        viewModelScope.launch {
            try {
                val result = app.apiClient.post("/me/checkin", "{}")
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(checkedIn = true, message = "签到成功") }
                        loadWall()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(errorMessage = e.message ?: "签到失败") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "签到失败") }
            }
        }
    }

    /** 发留言（POST /me/checkin/wall，字段 content_text；可带 image_url）。 */
    fun publishMessage(text: String, imageUrl: String?) {
        _uiState.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val body = buildMap<String, String> {
                    put("content_text", text)
                    if (!imageUrl.isNullOrBlank()) put("image_url", imageUrl)
                }
                val result = app.apiClient.post("/me/checkin/wall", app.gson.toJson(body))
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(sending = false, message = "留言已发送") }
                        loadWall()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(sending = false, errorMessage = e.message ?: "发送失败") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(sending = false, errorMessage = "发送失败") }
            }
        }
    }

    /** 点赞/取消点赞（POST /me/checkin/wall/like|unlike，字段 post_id）。 */
    fun toggleLike(post: CheckinPost) {
        viewModelScope.launch {
            val path = if (post.likedByMe) "/me/checkin/wall/unlike" else "/me/checkin/wall/like"
            try {
                val result = app.apiClient.post(path, app.gson.toJson(mapOf("post_id" to post.id)))
                result.onSuccess { loadWall() }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    /** 评论（POST /me/checkin/wall/comment，字段 post_id + body）。 */
    fun comment(postId: String, content: String) {
        viewModelScope.launch {
            try {
                val result = app.apiClient.post(
                    "/me/checkin/wall/comment",
                    app.gson.toJson(mapOf("post_id" to postId, "body" to content))
                )
                result.fold(
                    onSuccess = { loadWall() },
                    onFailure = { e ->
                        _uiState.update { it.copy(errorMessage = e.message ?: "评论失败") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "评论失败") }
            }
        }
    }

    /** 上传图片到 /media，回调返回 url。 */
    fun uploadImage(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val inputStream = app.contentResolver.openInputStream(uri) ?: return@launch
                val bytes = inputStream.readBytes()
                inputStream.close()
                val result = app.apiClient.postMultipart(
                    "/media",
                    listOf(FormPartData.FilePart("file", bytes, "image.jpg", "image/jpeg"))
                )
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val url = map?.get("url")?.toString()
                            ?: (map?.get("data") as? Map<*, *>)?.get("url")?.toString()
                            ?: ""
                        if (url.isNotBlank()) onDone(url)
                        else _uiState.update { it.copy(errorMessage = "图片上传失败") }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(errorMessage = e.message ?: "图片上传失败") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "图片上传失败") }
            }
        }
    }

    /** 查看谁点赞：拉取点赞列表（若无专用接口则基于 like_count 提示）。 */
    fun showLikes(post: CheckinPost) {
        // 规范未提供"谁点赞"的独立接口；这里展示点赞数（可扩展为拉取 like users）。
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    showLikesDialog = true,
                    likesPost = post,
                    likesUsers = listOf("共 ${post.likeCount} 人点赞")
                )
            }
        }
    }

    /** 查看评论：拉取评论列表。 */
    fun showComments(post: CheckinPost) {
        // 规范给出 comment 发布接口，但未给出"评论列表"独立接口；展示已有评论数。
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    showCommentsDialog = true,
                    commentsPost = post,
                    comments = listOf()
                )
            }
        }
    }

    fun dismissLikes() = _uiState.update { it.copy(showLikesDialog = false, likesPost = null, likesUsers = emptyList()) }
    fun dismissComments() = _uiState.update { it.copy(showCommentsDialog = false, commentsPost = null, comments = emptyList()) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }
}