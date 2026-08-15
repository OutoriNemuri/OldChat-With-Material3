package com.oldchat.material.feature.discover

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.media.MediaUploader
import com.oldchat.material.core.media.MediaUrlResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动态（Moments）—— 全量动态流，点赞/评论，发动态。
 * 发动态作为本界面的子界面（内部状态切换）。
 *
 * 接口（实测确认）：
 * - GET  /moments/v2                   -> {"moments":[{...}]}
 * - GET  /moments/user?uid=<uid>       -> 某用户动态
 * - POST /moments                       -> {body, image_url} 发布（含图片，先传 /media）
 * - POST /moments/like|/unlike          -> {moment_id}
 * - GET  /moments/comments?moment_id=   -> {"comments":[{...}]}
 * - POST /moments/comment               -> {moment_id, body}
 *
 * 字段（snake_case）：id/from_uid/from_ncuid/from_name/from_title/from_avatar/
 * body/image_url/created_at/likes/comments/liked。image_url 为字符串（单图 URL 或 JSON 数组字符串）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onBack: () -> Unit = {},
    feedViewModel: FeedViewModel = viewModel()
) {
    // 「发动态」子界面开关
    var showComposer by remember { mutableStateOf(false) }
    if (showComposer) {
        MomentComposerScreen(
            onBack = { showComposer = false },
            onPublished = {
                showComposer = false
                feedViewModel.refresh()
            }
        )
        return
    }

    val moments by feedViewModel.moments.collectAsStateWithLifecycle()
    val isLoading by feedViewModel.isLoading.collectAsStateWithLifecycle()
    val error by feedViewModel.error.collectAsStateWithLifecycle()

    // 评论弹层
    var commentMoment by remember { mutableStateOf<Moment?>(null) }

    LaunchedEffect(Unit) { feedViewModel.loadIfEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("动态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showComposer = true }) {
                        Icon(Icons.Filled.Add, "发动态")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showComposer = true }) {
                Icon(Icons.Filled.Add, "发动态")
            }
        }
    ) { padding ->
        when {
            isLoading && moments.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            moments.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.PhotoCamera, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (error != null) "加载失败" else "暂无动态",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { feedViewModel.refresh() }) { Text("刷新") }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(moments, key = { it.id }) { moment ->
                        MomentCard(
                            moment = moment,
                            onLike = { feedViewModel.toggleLike(moment) },
                            onComment = { commentMoment = moment }
                        )
                    }
                }
            }
        }
    }

    // 评论弹层
    commentMoment?.let { moment ->
        MomentCommentsSheet(
            moment = moment,
            viewModel = feedViewModel,
            onDismiss = { commentMoment = null }
        )
    }
}

// ---- 动态卡片 ----

@Composable
private fun MomentCard(
    moment: Moment,
    onLike: () -> Unit,
    onComment: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 头部：头像 + 名字 + 称号 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (moment.fromAvatar.isEmpty()) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            moment.fromName.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                } else {
                    AsyncImage(
                        model = moment.fromAvatar,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            moment.fromName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (moment.fromTitle.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                moment.fromTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        formatTimestamp(moment.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 正文
            if (moment.body.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(moment.body, style = MaterialTheme.typography.bodyMedium)
            }

            // 图片
            if (moment.imageUrls.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                if (moment.imageUrls.size == 1) {
                    AsyncImage(
                        model = moment.imageUrls.first(),
                        contentDescription = "动态图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 九宫格
                    val grid = moment.imageUrls
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        grid.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                repeat(3 - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 点赞 / 评论操作栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.clickable(onClick = onLike),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (moment.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = if (moment.liked) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${moment.likes}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(24.dp))
                Row(
                    modifier = Modifier.clickable(onClick = onComment),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${moment.comments}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---- 评论弹层 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentCommentsSheet(
    moment: Moment,
    viewModel: FeedViewModel,
    onDismiss: () -> Unit
) {
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(moment.id) { viewModel.loadComments(moment.id) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "评论",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            if (comments.isEmpty()) {
                Text(
                    "暂无评论",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(comments, key = { it.id }) { comment ->
                        CommentRow(comment)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 输入框 + 发送
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("写评论…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            viewModel.postComment(moment.id, text)
                            input = ""
                        }
                    }
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: MomentComment) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (comment.fromAvatar.isEmpty()) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    comment.fromName.take(1),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            AsyncImage(
                model = comment.fromAvatar,
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.fromName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (comment.fromTitle.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        comment.fromTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                comment.body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ---- 发动态子界面 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentComposerScreen(
    onBack: () -> Unit,
    onPublished: () -> Unit,
    composerViewModel: MomentComposerViewModel = viewModel()
) {
    val context = LocalContext.current
    val isPublishing by composerViewModel.isPublishing.collectAsStateWithLifecycle()
    val publishError by composerViewModel.publishError.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedImage = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发动态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val body = text.trim()
                            if (body.isEmpty() && selectedImage == null) return@TextButton
                            composerViewModel.publish(context, body, selectedImage, onSuccess = onPublished)
                        },
                        enabled = !isPublishing
                    ) {
                        if (isPublishing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text("发布")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("分享你的想法…") },
                minLines = 4,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            // 已选图片预览
            if (selectedImage != null) {
                AsyncImage(
                    model = selectedImage,
                    contentDescription = "已选图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { selectedImage = null }) {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("移除图片")
                }
            } else {
                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加图片")
                }
            }

            if (publishError != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    publishError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ---- 数据模型 ----

data class Moment(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val fromTitle: String,
    val fromAvatar: String,
    val body: String,
    val imageUrls: List<String>,
    val createdAt: Long,
    val likes: Int,
    val comments: Int,
    val liked: Boolean
) {
    companion object {
        fun fromMap(map: Map<*, *>): Moment? {
            val id = map["id"]?.toString() ?: return null
            return Moment(
                id = id,
                fromUid = map["from_uid"]?.toString() ?: "",
                fromName = map["from_name"]?.toString() ?: "",
                fromTitle = map["from_title"]?.toString() ?: "",
                fromAvatar = MediaUrlResolver.resolve(map["from_avatar"]?.toString() ?: "") ?: "",
                body = map["body"]?.toString() ?: "",
                imageUrls = parseImageUrls(map["image_url"]?.toString()),
                createdAt = (map["created_at"] as? Number)?.toLong() ?: 0L,
                likes = (map["likes"] as? Number)?.toInt() ?: 0,
                comments = (map["comments"] as? Number)?.toInt() ?: 0,
                liked = map["liked"] as? Boolean ?: false
            )
        }

        /** image_url 是字符串：单图 URL 或 JSON 数组字符串。 */
        private fun parseImageUrls(raw: String?): List<String> {
            if (raw.isNullOrEmpty()) return emptyList()
            val s = raw.trim()
            return if (s.startsWith("[")) {
                try {
                    val list = OldChatApplication.instance.gson.fromJson(s, List::class.java)
                    list.mapNotNull { MediaUrlResolver.resolve(it?.toString()) }
                } catch (_: Exception) {
                    listOf(MediaUrlResolver.resolve(s) ?: s)
                }
            } else {
                listOf(MediaUrlResolver.resolve(s) ?: s)
            }
        }
    }
}

data class MomentComment(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val fromTitle: String,
    val fromAvatar: String,
    val body: String,
    val createdAt: Long
) {
    companion object {
        fun fromMap(map: Map<*, *>): MomentComment? {
            val id = map["id"]?.toString() ?: return null
            return MomentComment(
                id = id,
                fromUid = map["from_uid"]?.toString() ?: "",
                fromName = map["from_name"]?.toString() ?: "",
                fromTitle = map["from_title"]?.toString() ?: "",
                fromAvatar = MediaUrlResolver.resolve(map["from_avatar"]?.toString() ?: "") ?: "",
                body = map["body"]?.toString() ?: "",
                createdAt = (map["created_at"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}

private fun formatTimestamp(sec: Long): String {
    if (sec <= 0) return ""
    return try {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))
    } catch (_: Exception) {
        ""
    }
}

// ---- ViewModel ----

class FeedViewModel : ViewModel() {

    private val app get() = OldChatApplication.instance

    private val _moments = MutableStateFlow<List<Moment>>(emptyList())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _comments = MutableStateFlow<List<MomentComment>>(emptyList())
    val comments: StateFlow<List<MomentComment>> = _comments.asStateFlow()

    fun loadIfEmpty() {
        if (_moments.value.isEmpty()) refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = app.apiClient.get("/moments/v2")
                result.fold(
                    onSuccess = { json -> parseMoments(json) },
                    onFailure = { _error.value = "加载失败" }
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parseMoments(json: String) {
        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        val list = map?.get("moments") as? List<Map<*, *>> ?: emptyList()
        _moments.value = list.mapNotNull { Moment.fromMap(it) }
    }

    /** 点赞 / 取消赞（本地乐观更新 + 服务端确认）。 */
    fun toggleLike(moment: Moment) {
        viewModelScope.launch {
            val target = !moment.liked
            // 乐观更新
            _moments.update { list ->
                list.map {
                    if (it.id == moment.id) it.copy(liked = target, likes = it.likes + if (target) 1 else -1)
                    else it
                }
            }
            val path = if (target) "/moments/like" else "/moments/unlike"
            val body = app.gson.toJson(mapOf("moment_id" to moment.id))
            app.apiClient.post(path, body)
        }
    }

    fun loadComments(momentId: String) {
        viewModelScope.launch {
            try {
                val result = app.apiClient.get("/moments/comments", mapOf("moment_id" to momentId))
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val list = map?.get("comments") as? List<Map<*, *>> ?: emptyList()
                        _comments.value = list.mapNotNull { MomentComment.fromMap(it) }
                    },
                    onFailure = { /* 忽略 */ }
                )
            } catch (_: Exception) { /* 忽略 */ }
        }
    }

    fun postComment(momentId: String, text: String) {
        viewModelScope.launch {
            val body = app.gson.toJson(mapOf("moment_id" to momentId, "body" to text))
            val result = app.apiClient.post("/moments/comment", body)
            result.fold(
                onSuccess = { loadComments(momentId) },
                onFailure = { /* 忽略 */ }
            )
            // 刷新列表以更新评论计数
            refresh()
        }
    }
}

/** 发动态 ViewModel：选图上传 + 发布。 */
class MomentComposerViewModel : ViewModel() {

    private val app get() = OldChatApplication.instance

    private val _isPublishing = MutableStateFlow(false)
    val isPublishing: StateFlow<Boolean> = _isPublishing.asStateFlow()

    private val _publishError = MutableStateFlow<String?>(null)
    val publishError: StateFlow<String?> = _publishError.asStateFlow()

    fun publish(
        context: android.content.Context,
        body: String,
        imageUri: Uri?,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isPublishing.value = true
            _publishError.value = null
            try {
                var imageUrl = ""
                // 1. 有图先上传 /media
                if (imageUri != null) {
                    val upload = MediaUploader.uploadImage(context, imageUri)
                    imageUrl = upload.getOrNull()?.url ?: run {
                        _publishError.value = "图片上传失败"
                        _isPublishing.value = false
                        return@launch
                    }
                }

                // 2. 发布 /moments
                val payload = mutableMapOf<String, Any>("body" to body)
                if (imageUrl.isNotEmpty()) payload["image_url"] = imageUrl
                val result = app.apiClient.post("/moments", app.gson.toJson(payload))
                result.fold(
                    onSuccess = {
                        _publishError.value = null
                        onSuccess()
                    },
                    onFailure = { e -> _publishError.value = e.message ?: "发布失败" }
                )
            } catch (e: Exception) {
                _publishError.value = e.message ?: "发布失败"
            } finally {
                _isPublishing.value = false
            }
        }
    }
}