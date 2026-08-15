package com.oldchat.material.feature.discover

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.media.MediaUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 下载歌曲音频到本地（用于"我的下载"）。
 *
 * API 29+：写入 MediaStore.Downloads（公共下载目录）。
 * API < 29：写入公共 Downloads 目录 + 媒体扫描。
 */
object DownloadUtil {

    private const val TAG = "DownloadUtil"

    /**
     * 下载歌曲音频文件。
     *
     * @param context 上下文
     * @param songUrl 歌曲相对路径（如 /v1/uploads/music/xxx.mp3）
     * @param fileName 目标文件名
     * @return 下载成功后的本地 URI 或路径（用于展示/播放），失败返回 null
     */
    suspend fun downloadSong(
        context: Context,
        songUrl: String,
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 优先使用登录/文件服务器（动态跟随）
            val fullUrl = OldChatApplication.instance.serverConfig.resolveMediaUrl(songUrl) ?: songUrl

            val token = OldChatApplication.instance.authManager.accessToken

            // 下载字节
            val bytes = downloadBytes(fullUrl, token, songUrl)
                ?: return@withContext null

            // 保存到 MediaStore.Downloads（API 29+）或公共目录（API < 29）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloads(context, fileName, bytes)
            } else {
                saveToLegacyDownloads(fileName, bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadSong failed", e)
            null
        }
    }

    /**
     * 下载字节，带 token，支持候选降级。
     */
    private suspend fun downloadBytes(url: String, token: String?, songUrl: String): ByteArray? {
        val candidates = mutableListOf<String>()
        candidates.add(url)
        // 追加其他候选
        MediaUrlResolver.resolveCandidates(songUrl).forEach { c ->
            if (c !in candidates) candidates.add(c)
        }

        for (candidate in candidates) {
            try {
                val connection = java.net.URL(candidate).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                if (token != null) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                connection.connect()
                val code = connection.responseCode
                if (code == 200) {
                    val bytes = connection.inputStream.readBytes()
                    connection.disconnect()
                    return bytes
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "candidate failed: $candidate -> ${e.message}")
            }
        }
        return null
    }

    private fun saveToDownloads(context: Context, fileName: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val safeName = sanitizeFileName(fileName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val insertedUri = uri ?: return null
        return try {
            resolver.openOutputStream(insertedUri)?.use { out ->
                out.write(bytes)
            }
            insertedUri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "save to downloads failed", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(fileName: String, bytes: ByteArray): String? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val safeName = sanitizeFileName(fileName)
        val file = File(dir, safeName)
        return try {
            FileOutputStream(file).use { out ->
                out.write(bytes)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "save legacy failed", e)
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}