package com.oldchat.material.feature.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 音乐广场：搜索框 + 4 个并排按钮 + Cover Flow 推荐卡片。
 * 点击歌曲、点击"正在播放"进入播放页（MusicPlayerScreen）。
 *
 * @param initialPlayTitle 从聊天音乐消息跳转时传入的歌名，进入后自动搜索并播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSquareScreen(
    onBack: () -> Unit = {},
    initialPlayTitle: String? = null,
    musicViewModel: MusicViewModel = viewModel()
) {
    val songs by musicViewModel.songs.collectAsStateWithLifecycle()
    val recommendedSongs by musicViewModel.recommendedSongs.collectAsStateWithLifecycle()
    val isLoading by musicViewModel.isLoading.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var currentSong by remember { mutableStateOf<MusicSong?>(null) }
    // 子页面：null=主列表；"upload"=上传页；"my_downloads"=我的下载；"my_uploads"=我的上传列表
    var subPage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { musicViewModel.loadSongs() }

    // 从聊天音乐消息跳转：自动搜索并播放
    LaunchedEffect(initialPlayTitle) {
        if (initialPlayTitle != null) {
            searchQuery = initialPlayTitle
            musicViewModel.searchAndPlay(initialPlayTitle) { song ->
                currentSong = song
            }
        }
    }

    // 播放页子导航
    if (currentSong != null) {
        MusicPlayerScreen(
            song = currentSong!!,
            onBack = { currentSong = null }
        )
        return
    }

    // 上传页
    if (subPage == "upload") {
        MusicUploadScreen(
            onBack = { subPage = null },
            onUploaded = {
                subPage = null
                musicViewModel.loadSongs()
            }
        )
        return
    }

    // 我的下载页
    if (subPage == "my_downloads") {
        MyDownloadsScreen(onBack = { subPage = null })
        return
    }

    // 我的上传列表页
    if (subPage == "my_uploads") {
        MyUploadsScreen(
            onBack = { subPage = null },
            songs = songs,
            isLoading = isLoading,
            onRefresh = { musicViewModel.loadMyUploads() },
            onPlay = { song ->
                musicViewModel.playSong(song)
                currentSong = song
            },
            onDelete = { song ->
                musicViewModel.deleteSong(song.id) { ok, _ ->
                    if (ok) musicViewModel.loadMyUploads()
                }
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐广场") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索框 + 搜索键
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { musicViewModel.searchSongs(searchQuery) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 4 个并排按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SquareActionButton("正在播放", Icons.Filled.PlayCircle, onClick = {
                    val s = musicViewModel.currentSong.value
                    if (s != null) currentSong = s
                }, modifier = Modifier.weight(1f))
                SquareActionButton("上传音乐", Icons.Filled.Upload, onClick = {
                    subPage = "upload"
                }, modifier = Modifier.weight(1f))
                SquareActionButton("我的上传", Icons.Filled.LibraryMusic, onClick = {
                    musicViewModel.loadMyUploads()
                    subPage = "my_uploads"
                }, modifier = Modifier.weight(1f))
                SquareActionButton("我的下载", Icons.Filled.Download, onClick = {
                    subPage = "my_downloads"
                }, modifier = Modifier.weight(1f))
            }

            // 内容区
            if (isLoading && songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.MusicOff, null, modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无音乐", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { musicViewModel.loadSongs() }) { Text("刷新") }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Cover Flow 推荐卡片
                    item {
                        Text(
                            "每日推荐",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        CoverFlowRow(
                            songs = recommendedSongs.take(10),
                            onSongClick = { song ->
                                musicViewModel.playSong(song)
                                currentSong = song
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "全部歌曲",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // 全部歌曲列表
                    items(songs, key = { it.id }) { song ->
                        MusicCard(
                            song = song,
                            onPlay = {
                                musicViewModel.playSong(song)
                                currentSong = song
                            },
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜索音乐") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, "搜索")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onSearch) {
            Text("搜索")
        }
    }
}

@Composable
private fun SquareActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * Apple Cover Flow 样式的横向封面走马灯（伪 3D 旋转变换）。
 */
@Composable
private fun CoverFlowRow(
    songs: List<MusicSong>,
    onSongClick: (MusicSong) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return

    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(songs.size) { index ->
            val song = songs[index]
            CoverFlowCard(
                song = song,
                rotationY = 0f,
                onClick = { onSongClick(song) }
            )
        }
    }
}

@Composable
private fun CoverFlowCard(
    song: MusicSong,
    rotationY: Float,
    onClick: () -> Unit
) {
    val animatedRotation by animateFloatAsState(rotationY, label = "coverFlowRotation")
    Card(
        modifier = Modifier
            .size(width = 140.dp, height = 140.dp)
            .graphicsLayer {
                this.rotationY = animatedRotation
                cameraDistance = 8f * density
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (song.coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(6.dp)
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MusicCard(
    song: MusicSong,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (song.coverUrl.isNotEmpty()) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("${song.likeCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        formatDuration(song.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = durationMs / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}

// ---- Music ViewModel ----

class MusicViewModel : ViewModel() {

    private val app get() = OldChatApplication.instance

    // 每日推荐（固定，loadSongs 填充，不受搜索/我的上传影响）
    private val _recommendedSongs = MutableStateFlow<List<MusicSong>>(emptyList())
    val recommendedSongs: StateFlow<List<MusicSong>> = _recommendedSongs.asStateFlow()

    // 当前展示列表（初始 = 每日推荐；搜索/我的上传后变化）
    private val _songs = MutableStateFlow<List<MusicSong>>(emptyList())
    val songs: StateFlow<List<MusicSong>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentSong = MutableStateFlow<MusicSong?>(null)
    val currentSong: StateFlow<MusicSong?> = _currentSong.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadSongs() {
        viewModelScope.launch {
            if (_recommendedSongs.value.isEmpty()) {
                // 本地缓存秒开
                cachedSongs()?.let { list ->
                    _recommendedSongs.value = list
                    _songs.value = list
                }
            }
            _isLoading.value = _songs.value.isEmpty()
            try {
                val result = app.apiClient.get("/music/plaza", mapOf("limit" to "50"))
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val items = map?.get("items") as? List<Map<*, *>> ?: emptyList()
                        val list = items.mapNotNull { MusicSong.fromMap(it) }
                        _recommendedSongs.value = list
                        _songs.value = list
                        cacheSongs(json)
                    },
                    onFailure = { _error.value = "加载失败" }
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private val CACHE_KEY = "music_plaza_songs"

    private fun cachedSongs(): List<MusicSong>? {
        return try {
            val json = app.cacheManager.pageCache.read(CACHE_KEY) ?: return null
            val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
            val items = map?.get("items") as? List<Map<*, *>> ?: return null
            val list = items.mapNotNull { MusicSong.fromMap(it) }
            list.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun cacheSongs(json: String) {
        try { app.cacheManager.pageCache.write(CACHE_KEY, json) } catch (_: Exception) {}
    }

    fun searchSongs(query: String) {
        val q = query.trim()
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val params = if (q.isEmpty()) emptyMap() else mapOf("q" to q)
                val result = app.apiClient.get("/music/plaza", params)
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val items = map?.get("items") as? List<Map<*, *>> ?: emptyList()
                        // 只更新展示列表，不动推荐
                        _songs.value = items.mapNotNull { MusicSong.fromMap(it) }
                    },
                    onFailure = { _error.value = "搜索失败" }
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMyUploads() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = app.apiClient.get("/music/plaza/mine")
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val items = map?.get("items") as? List<Map<*, *>> ?: emptyList()
                        // 只更新展示列表，不动推荐
                        _songs.value = items.mapNotNull { MusicSong.fromMap(it) }
                    },
                    onFailure = { _error.value = "加载失败" }
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 播放歌曲：设置当前歌曲并交由播放页处理。
     */
    fun playSong(song: MusicSong) {
        _currentSong.value = song
    }

    /**
     * 按歌名搜索并播放（供聊天音乐消息跳转使用）。
     * 搜索成功后回调 song，由 UI 导航到播放页。
     */
    fun searchAndPlay(title: String, onFound: (MusicSong) -> Unit) {
        val q = title.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = app.apiClient.get("/music/plaza", mapOf("q" to q))
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val items = map?.get("items") as? List<Map<*, *>> ?: emptyList()
                        val list = items.mapNotNull { MusicSong.fromMap(it) }
                        _songs.value = list
                        // 优先精确匹配歌名，否则取第一条
                        val match = list.firstOrNull { it.title.equals(q, ignoreCase = true) }
                            ?: list.firstOrNull()
                        if (match != null) {
                            playSong(match)
                            onFound(match)
                        }
                    },
                    onFailure = { _error.value = "搜索失败" }
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 上传歌曲（multipart：name + file + cover + lyrics）。
     * 上传页面留空，此方法供后续上传页调用。
     */
    fun uploadSong(
        name: String,
        audioBytes: ByteArray?,
        audioFileName: String,
        audioMimeType: String,
        coverBytes: ByteArray?,
        coverFileName: String,
        coverMimeType: String,
        lyricsBytes: ByteArray?,
        lyricsFileName: String,
        lyricsMimeType: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val parts = mutableListOf<FormPartData>()
                parts.add(FormPartData.TextPart("name", name))
                if (audioBytes != null) {
                    parts.add(FormPartData.FilePart("file", audioBytes, audioFileName, audioMimeType))
                }
                if (coverBytes != null) {
                    parts.add(FormPartData.FilePart("cover", coverBytes, coverFileName, coverMimeType))
                }
                if (lyricsBytes != null) {
                    parts.add(FormPartData.FilePart("lyrics", lyricsBytes, lyricsFileName, lyricsMimeType))
                }
                val result = app.apiClient.postMultipart("/music/plaza/upload", parts)
                result.fold(
                    onSuccess = { json -> onResult(true, json) },
                    onFailure = { e -> onResult(false, e.message) }
                )
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    /**
     * 删除自己上传的歌曲（owner）。服务端 POST /music/plaza/delete，body {id}。
     */
    fun deleteSong(songId: String, onResult: (Boolean, String?) -> Unit) {
        if (songId.isBlank()) return
        viewModelScope.launch {
            try {
                val body = app.gson.toJson(mapOf("id" to songId))
                val result = app.apiClient.post("/music/plaza/delete", body)
                result.fold(
                    onSuccess = { onResult(true, null) },
                    onFailure = { e -> onResult(false, e.message) }
                )
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    /**
     * 点赞/取消点赞一首歌。
     */
    fun toggleLike(songId: String, currentlyLiked: Boolean, onResult: (Boolean, Boolean) -> Unit) {
        if (songId.isBlank()) return
        viewModelScope.launch {
            try {
                val path = if (currentlyLiked) "/music/plaza/unlike" else "/music/plaza/like"
                val body = app.gson.toJson(mapOf("id" to songId))
                val result = app.apiClient.post(path, body)
                result.fold(
                    onSuccess = {
                        // 乐观切换
                        val idx = _songs.value.indexOfFirst { it.id == songId }
                        if (idx >= 0) {
                            val s = _songs.value[idx]
                            _songs.value = _songs.value.toMutableList().also { list ->
                                list[idx] = s.copy(
                                    liked = !currentlyLiked,
                                    likeCount = if (!currentlyLiked) s.likeCount + 1 else (s.likeCount - 1).coerceAtLeast(0)
                                )
                            }
                        }
                        onResult(true, !currentlyLiked)
                    },
                    onFailure = { e -> onResult(false, currentlyLiked) }
                )
            } catch (e: Exception) {
                onResult(false, currentlyLiked)
            }
        }
    }
}

/**
 * 音乐广场歌曲模型（字段映射于实测接口）。
 */
data class MusicSong(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val audioUrl: String,
    val lyricsUrl: String,
    val durationMs: Long,
    val likeCount: Int,
    val ownerName: String,
    val liked: Boolean = false,
    val canDelete: Boolean = false,
    val ownerUid: String = "",
    val ownerAvatar: String = "",
    val ownerTitle: String = "",
    val sizeBytes: Long = 0L
) {
    companion object {
        fun fromMap(map: Map<*, *>): MusicSong? {
            val id = map["id"]?.toString() ?: return null
            val rawCover = map["cover_url"]?.toString() ?: ""
            val rawAudio = map["song_url"]?.toString() ?: ""
            val rawLyrics = map["lyrics_url"]?.toString() ?: ""
            val rawOwnerAvatar = map["owner_avatar"]?.toString() ?: ""
            return MusicSong(
                id = id,
                title = map["name"]?.toString() ?: "",
                artist = map["owner_name"]?.toString() ?: "",
                coverUrl = resolveMedia(rawCover),
                audioUrl = resolveMedia(rawAudio),
                lyricsUrl = resolveMedia(rawLyrics),
                durationMs = (map["duration_ms"] as? Number)?.toLong() ?: 0L,
                likeCount = (map["likes"] as? Number)?.toInt() ?: 0,
                ownerName = map["owner_name"]?.toString() ?: "",
                liked = (map["liked"] as? Boolean) ?: false,
                canDelete = (map["can_delete"] as? Boolean) ?: false,
                ownerUid = map["owner_uid"]?.toString() ?: "",
                ownerAvatar = resolveMedia(rawOwnerAvatar),
                ownerTitle = map["owner_title"]?.toString() ?: "",
                sizeBytes = (map["size_bytes"] as? Number)?.toLong() ?: 0L
            )
        }

        /**
         * 将相对路径解析为完整可访问 URL（动态跟随登录/文件服务器）。
         */
        private fun resolveMedia(path: String): String {
            if (path.isEmpty()) return ""
            return OldChatApplication.instance.serverConfig.resolveMediaUrl(path) ?: ""
        }
    }
}