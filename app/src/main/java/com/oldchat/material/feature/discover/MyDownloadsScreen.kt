package com.oldchat.material.feature.discover

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 我的下载：展示已下载到系统下载目录（MediaStore.Downloads）的音乐文件。
 * 点击可播放；长按/删除按钮可删除本地文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDownloadsScreen(
    onBack: () -> Unit = {},
    onPlay: (DownloadedSong) -> Unit = {}
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<DownloadedSong>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            songs = scanDownloadedMusic(context)
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (songs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.MusicOff, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text("暂无下载的音乐", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("在播放页点下载按钮即可下载歌曲", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(songs, key = { it.uri }) { song ->
                        DownloadedSongCard(
                            song = song,
                            onPlay = { onPlay(song) },
                            onDelete = {
                                scope.launch {
                                    deleteDownloaded(context, song)
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedSongCard(
    song: DownloadedSong,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(formatFileSize(song.sizeBytes), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.PlayArrow, "播放", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 已下载歌曲（本地文件）。 */
data class DownloadedSong(
    val uri: String,
    val title: String,
    val artist: String,
    val sizeBytes: Long
)

/**
 * 扫描 MediaStore.Downloads 里的音频文件（.mp3/.m4a/.wav/.flac/.ogg）。
 */
private suspend fun scanDownloadedMusic(context: Context): List<DownloadedSong> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<DownloadedSong>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA
            )
            val cursor = context.contentResolver.query(
                collection, projection, null, null, null
            ) ?: return@withContext result

            cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: "unknown"
                    val title = it.getString(titleCol) ?: name.substringBeforeLast('.')
                    val size = it.getLong(sizeCol)
                    // 只保留音频文件
                    val lower = name.lowercase()
                    if (!(lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                            lower.endsWith(".wav") || lower.endsWith(".flac") ||
                            lower.endsWith(".ogg") || lower.endsWith(".aac"))
                    ) continue
                    val uri = "${collection}/${id}"
                    result.add(
                        DownloadedSong(
                            uri = uri,
                            title = title,
                            artist = "", // MediaStore 下载目录通常无艺术家信息
                            sizeBytes = size
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // fallback：扫描公共 Downloads 目录
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir.exists()) {
                    dir.listFiles()?.forEach { f ->
                        val lower = f.name.lowercase()
                        if (f.isFile && (lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                                lower.endsWith(".wav") || lower.endsWith(".flac") ||
                                lower.endsWith(".ogg"))
                        ) {
                            result.add(
                                DownloadedSong(
                                    uri = f.absolutePath,
                                    title = f.name.substringBeforeLast('.'),
                                    artist = "",
                                    sizeBytes = f.length()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        result
    }

/** 删除本地下载文件。 */
private suspend fun deleteDownloaded(context: Context, song: DownloadedSong) {
    withContext(Dispatchers.IO) {
        try {
            if (song.uri.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(song.uri), null, null)
            } else {
                File(song.uri).delete()
            }
        } catch (_: Exception) {}
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    return "${bytes / (1024 * 1024)} MB"
}