package com.oldchat.material.feature.discover

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 上传音乐页面：选音频文件（必填）+ 可选封面 + 可选歌词 + 歌名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicUploadScreen(
    onBack: () -> Unit = {},
    onUploaded: () -> Unit = {},
    musicViewModel: MusicViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var songName by remember { mutableStateOf("") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("") }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var coverName by remember { mutableStateOf("") }
    var lyricsUri by remember { mutableStateOf<Uri?>(null) }
    var lyricsName by remember { mutableStateOf("") }

    var uploading by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            audioUri = uri
            audioName = queryDisplayName(context, uri) ?: "audio"
            if (songName.isBlank()) {
                songName = audioName.substringBeforeLast('.')
            }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coverUri = uri
            coverName = queryDisplayName(context, uri) ?: "cover.jpg"
        }
    }
    val lyricsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lyricsUri = uri
            lyricsName = queryDisplayName(context, uri) ?: "lyrics.lrc"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上传音乐") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = songName,
                onValueChange = { songName = it },
                label = { Text("歌曲名（必填）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            // 音频文件（必填）
            OutlinedButton(
                onClick = { audioPicker.launch("audio/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (audioUri == null) "选择音频文件（必填）" else "音频：$audioName")
            }

            Spacer(Modifier.height(12.dp))

            // 封面（可选）
            OutlinedButton(
                onClick = { coverPicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (coverUri == null) "选择封面（可选）" else "封面：$coverName")
            }

            Spacer(Modifier.height(12.dp))

            // 歌词（可选）
            OutlinedButton(
                onClick = { lyricsPicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (lyricsUri == null) "选择歌词文件（可选）" else "歌词：$lyricsName")
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val audio = audioUri
                    if (audio == null || songName.isBlank()) {
                        resultMsg = "请先填写歌曲名并选择音频文件"
                        return@Button
                    }
                    scope.launch {
                        uploading = true
                        resultMsg = null
                        val audioBytes = readBytes(context, audio)
                        val coverBytes = coverUri?.let { readBytes(context, it) }
                        val lyricsBytes = lyricsUri?.let { readBytes(context, it) }
                        musicViewModel.uploadSong(
                            name = songName.trim(),
                            audioBytes = audioBytes,
                            audioFileName = audioName,
                            audioMimeType = "audio/mpeg",
                            coverBytes = coverBytes,
                            coverFileName = coverName,
                            coverMimeType = "image/jpeg",
                            lyricsBytes = lyricsBytes,
                            lyricsFileName = lyricsName,
                            lyricsMimeType = "text/plain",
                            onResult = { ok, msg ->
                                uploading = false
                                resultMsg = if (ok) "上传成功" else "上传失败：${msg ?: "未知错误"}"
                                if (ok) onUploaded()
                            }
                        )
                    }
                },
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uploading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("上传中…")
                } else {
                    Icon(Icons.Filled.Upload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("上传")
                }
            }

            if (resultMsg != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    resultMsg!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (resultMsg == "上传成功") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 读取 Uri 字节（IO 线程）。 */
private suspend fun readBytes(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

/** 查询 Uri 显示名。 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) { null }
}