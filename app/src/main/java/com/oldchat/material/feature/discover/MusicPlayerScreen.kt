package com.oldchat.material.feature.discover

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import com.oldchat.material.service.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音乐播放界面（独立文件）。
 *
 * 功能：
 * - ExoPlayer 播放
 * - 左滑（HorizontalPager）看歌词
 * - 歌词滚动（lrc / ttml / xml）
 * - 下载歌曲
 * - 单次播放 / 单曲循环切换
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(
    song: MusicSong,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val player = remember { MusicPlayerHolder.obtain(context) }

    // 播放状态
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(if (song.durationMs > 0) song.durationMs else 0L) }
    var repeatOnce by remember { mutableStateOf(false) }

    // 歌词
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsLoaded by remember { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 下载状态
    var downloading by remember { mutableStateOf(false) }
    var downloadResult by remember { mutableStateOf<String?>(null) }

    // Pager（0 = 封面，1 = 歌词）
    val pagerState = rememberPagerState(pageCount = { 2 })

    // 加载歌词
    LaunchedEffect(song.lyricsUrl) {
        if (song.lyricsUrl.isEmpty()) {
            lyricsLoaded = true
            return@LaunchedEffect
        }
        val content = downloadLyrics(song.lyricsUrl)
        lyrics = if (content != null) LyricsParser.parse(content, song.lyricsUrl) else emptyList()
        lyricsLoaded = true
    }

    // 设置并播放歌曲（同一首歌不重新 prepare，避免从头播放）
    LaunchedEffect(song.audioUrl) {
        val holder = MusicPlayerHolder
        // 如果是同一首歌且已加载，则不重新 prepare（保持进度）
        if (holder.currentMediaUri == song.audioUrl && holder.isPrepared) {
            // 恢复播放状态，但不从头
            if (!player.isPlaying) player.play()
            return@LaunchedEffect
        }
        val token = OldChatApplication.instance.authManager.accessToken
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${token ?: ""}"))
        }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(song.audioUrl))
        player.repeatMode = if (repeatOnce) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        holder.currentMediaUri = song.audioUrl
        holder.isPrepared = true
        // 记录当前歌曲并启动前台服务（通知栏控制）
        MusicPlayerHolder.currentTitle = song.title
        MusicPlayerHolder.currentArtist = song.artist
        MusicPlayerHolder.currentRepeatMode = repeatOnce
        MusicPlaybackService.start(context, song.title, song.artist)
    }

    // 轮询播放进度（驱动歌词滚动 + 刷新播放状态）
    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = player.isPlaying
            positionMs = player.currentPosition
            val d = player.duration
            if (d > 0) durationMs = d
            // 自动滚动歌词到当前行
            if (lyrics.isNotEmpty()) {
                val idx = LyricsParser.findCurrentLineIndex(lyrics, positionMs)
                if (idx >= 0) {
                    lyricListState.animateScrollToItem(maxOf(0, idx - 2))
                }
            }
            delay(300)
        }
    }

    // 添加播放器监听（播放结束处理单次/循环）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 单次播放结束自动停
                if (playbackState == Player.STATE_ENDED && !repeatOnce) {
                    // 保持结束状态，不循环
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                downloadResult = "播放失败：${error.message}"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            // 指示器（封面 / 歌词）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    if (pagerState.currentPage == 0) "● 封面" else "封面",
                    modifier = Modifier.padding(8.dp).clickable { scope.launch { pagerState.animateScrollToPage(0) } },
                    color = if (pagerState.currentPage == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    if (pagerState.currentPage == 1) "歌词 ●" else "歌词",
                    modifier = Modifier.padding(8.dp).clickable { scope.launch { pagerState.animateScrollToPage(1) } },
                    color = if (pagerState.currentPage == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 左滑区域
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> CoverPage(song)
                    1 -> LyricsPage(lyrics, lyricsLoaded, positionMs, lyricListState)
                }
            }

            // 进度条
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, if (durationMs > 0) durationMs.toFloat() else 1f),
                    onValueChange = { v ->
                        positionMs = v.toLong()
                        player.seekTo(v.toLong())
                    },
                    valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }

            // 控制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 单次/循环切换
                IconButton(onClick = {
                    repeatOnce = !repeatOnce
                    player.repeatMode = if (repeatOnce) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    MusicPlayerHolder.currentRepeatMode = repeatOnce
                }) {
                    Icon(
                        if (repeatOnce) Icons.Filled.RepeatOne else Icons.Outlined.Repeat,
                        contentDescription = if (repeatOnce) "单曲循环" else "单次播放",
                        tint = if (repeatOnce) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 播放/暂停
                FilledIconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (player.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // 下载
                IconButton(onClick = {
                    scope.launch {
                        downloading = true
                        downloadResult = null
                        val fileName = "${song.title}.mp3"
                        val result = DownloadUtil.downloadSong(context, song.audioUrl, fileName)
                        downloadResult = if (result != null) "已下载到下载目录" else "下载失败"
                        downloading = false
                    }
                }) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 下载状态提示
            if (downloadResult != null) {
                Text(
                    downloadResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CoverPage(song: MusicSong) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .graphicsLayer {
                        // 轻微 3D 效果
                        cameraDistance = 8f * density
                    }
            ) {
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
                        Icon(Icons.Outlined.MusicNote, null, modifier = Modifier.size(96.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(song.artist, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LyricsPage(
    lyrics: List<LyricLine>,
    loaded: Boolean,
    positionMs: Long,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val currentIndex = LyricsParser.findCurrentLineIndex(lyrics, positionMs)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp)
    ) {
        itemsIndexed(lyrics) { index, line ->
            val isCurrent = index == currentIndex
            Text(
                line.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp)
                    .animateContentSize(),
                textAlign = TextAlign.Center,
                style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}

/**
 * 下载歌词文本（带 token，IO 线程，候选降级：候选顺序保证 60.205 直连优先）。
 */
private suspend fun downloadLyrics(lyricsUrl: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val token = OldChatApplication.instance.authManager.accessToken
            // 候选顺序：先登录/文件服务器（动态跟随），再其他候选
            val candidates = mutableListOf<String>()
            // 1. 原始 URL（如果已经是完整 URL，直接用）；否则跟随登录服务器解析
            if (lyricsUrl.startsWith("http")) {
                candidates.add(lyricsUrl)
            } else {
                OldChatApplication.instance.serverConfig.resolveMediaUrl(lyricsUrl)?.let {
                    candidates.add(it)
                }
            }
            // 2. 追加 MediaUrlResolver 的其他候选（去重）
            com.oldchat.material.core.media.MediaUrlResolver.resolveCandidates(lyricsUrl).forEach { c ->
                if (c !in candidates) candidates.add(c)
            }

            for (url in candidates) {
                try {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    if (token != null) {
                        connection.setRequestProperty("Authorization", "Bearer $token")
                    }
                    connection.connect()
                    val code = connection.responseCode
                    if (code == 200) {
                        val text = connection.inputStream.readBytes().toString(Charsets.UTF_8)
                        connection.disconnect()
                        return@withContext text
                    }
                    connection.disconnect()
                } catch (_: Exception) {
                    // 继续下一个候选
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 全局音乐播放器持有者（单例）。
 *
 * 第一批：仅持有 ExoPlayer，退出播放页后继续播放。
 * 第二批：接入前台 Service + 通知栏。
 */
object MusicPlayerHolder {
    @Volatile
    private var player: ExoPlayer? = null

    // 当前已加载的媒体 URI（用于判断是否同一首歌）
    @Volatile
    var currentMediaUri: String? = null

    @Volatile
    var isPrepared: Boolean = false

    // 单曲循环开关（供前台服务与播放页共享）
    @Volatile
    var currentRepeatMode: Boolean = false

    // 当前播放歌曲标题/艺术家（供通知栏在服务未启动时也能读取）
    @Volatile
    var currentTitle: String = ""
    @Volatile
    var currentArtist: String = ""

    fun obtain(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            player ?: ExoPlayer.Builder(context).build().also { player = it }
        }
    }

    fun release() {
        synchronized(this) {
            player?.release()
            player = null
            currentMediaUri = null
            isPrepared = false
            currentRepeatMode = false
            currentTitle = ""
            currentArtist = ""
        }
    }
}