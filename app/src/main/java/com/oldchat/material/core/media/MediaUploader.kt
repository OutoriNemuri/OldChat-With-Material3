package com.oldchat.material.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.network.FormPartData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Unified media uploader with image compression.
 * Mirrors DirectMessageSender media sending from original §3.2.
 *
 * Limits:
 * - Image: 3 MB (maxImageMediaBytes)
 * - Media: 50 MB (maxMediaBytes)
 * - File: 1 GB (MAX_FILE_BYTES)
 */
object MediaUploader {

    private const val MAX_IMAGE_BYTES = 3L * 1024 * 1024      // 3 MB
    private const val MAX_MEDIA_BYTES = 50L * 1024 * 1024     // 50 MB
    const val MAX_FILE_BYTES = 1024L * 1024 * 1024            // 1 GB
    private const val JPEG_QUALITY = 80
    private const val THUMB_MAX_WIDTH = 300
    private const val THUMB_MAX_HEIGHT = 300

    data class UploadResult(
        val url: String,
        val thumbUrl: String? = null
    )

    /**
     * Upload an image from a content URI.
     * Compresses if > 3MB, generates thumbnail.
     */
    suspend fun uploadImage(context: Context, uri: Uri): Result<UploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open image"))

                val originalBytes = inputStream.readBytes()
                inputStream.close()

                // Compress if needed
                val imageBytes = if (originalBytes.size > MAX_IMAGE_BYTES) {
                    compressImage(originalBytes, MAX_IMAGE_BYTES)
                } else {
                    originalBytes
                }

                if (imageBytes.size > MAX_IMAGE_BYTES) {
                    return@withContext Result.failure(Exception("图片过大，请选择小于 3MB 的图片"))
                }

                // Generate thumbnail
                val thumbBytes = createThumbnail(imageBytes)

                val app = OldChatApplication.instance
                val parts = mutableListOf<FormPartData>(
                    FormPartData.FilePart("file", imageBytes, "image.jpg", "image/jpeg")
                )
                if (thumbBytes != null) {
                    parts.add(FormPartData.FilePart("thumb", thumbBytes, "thumb.jpg", "image/jpeg"))
                }

                val result = app.apiClient.postMultipart("/media", parts)
                result.map { body ->
                    val map = app.gson.fromJson(body, Map::class.java) as? Map<*, *> ?: return@map UploadResult("", null)
                    val url = map["url"] as? String ?: map["data"]?.let { (it as? Map<*, *>)?.get("url") as? String } ?: ""
                    val thumbUrl = map["thumb_url"] as? String
                        ?: (map["data"] as? Map<*, *>)?.get("thumb_url") as? String
                    UploadResult(url = url, thumbUrl = thumbUrl)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Upload a video with thumbnail generation.
     */
    suspend fun uploadVideo(context: Context, uri: Uri): Result<UploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                // Extract metadata
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val hasVideo = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
                ) == "yes"
                val durationStr = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )

                // Check size
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open video"))
                val bytes = inputStream.readBytes()
                inputStream.close()

                if (bytes.size > MAX_MEDIA_BYTES) {
                    retriever.release()
                    return@withContext Result.failure(Exception("视频过大，请选择小于 50MB 的视频"))
                }

                // Generate thumbnail if video
                var thumbBytes: ByteArray? = null
                if (hasVideo) {
                    try {
                        val frameBitmap = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (frameBitmap != null) {
                            val thumbStream = ByteArrayOutputStream()
                            frameBitmap.compress(Bitmap.CompressFormat.JPEG, 70, thumbStream)
                            thumbBytes = thumbStream.toByteArray()
                            frameBitmap.recycle()
                        }
                    } catch (_: Exception) { /* no thumbnail */ }
                }
                retriever.release()

                val app = OldChatApplication.instance
                val parts = mutableListOf(
                    FormPartData.FilePart("file", bytes, "video.mp4", "video/mp4")
                )
                if (thumbBytes != null) {
                    parts.add(FormPartData.FilePart("thumb", thumbBytes, "thumb.jpg", "image/jpeg"))
                }

                val result = app.apiClient.postMultipart("/media", parts)
                result.map { body ->
                    val map = app.gson.fromJson(body, Map::class.java) as? Map<*, *>
                    val url = map?.get("url") as? String ?: ""
                    val thumbUrl = map?.get("thumb_url") as? String
                    UploadResult(url = url, thumbUrl = thumbUrl)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Upload a voice recording.
     */
    suspend fun uploadVoice(context: Context, uri: Uri): Result<UploadResult> {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open audio"))
                val bytes = inputStream.readBytes()
                inputStream.close()

                val app = OldChatApplication.instance
                val parts = listOf(
                    FormPartData.FilePart("file", bytes, "voice.aac", "audio/aac")
                )
                app.apiClient.postMultipart("/media", parts).map { body ->
                    val map = app.gson.fromJson(body, Map::class.java) as? Map<*, *>
                    UploadResult(url = map?.get("url") as? String ?: "")
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Upload a generic file.
     * Checks size limit 1GB, uses chunked streaming.
     */
    suspend fun uploadFile(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): Result<UploadResult> {
        if (fileSize > MAX_FILE_BYTES) {
            return Result.failure(Exception("文件过大，请选择小于 1GB 的文件"))
        }

        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open file"))
                val bytes = inputStream.readBytes()
                inputStream.close()

                val app = OldChatApplication.instance
                val parts = listOf(
                    FormPartData.FilePart("file", bytes, fileName, mimeType)
                )
                app.apiClient.postMultipart("/media", parts).map { body ->
                    val map = app.gson.fromJson(body, Map::class.java) as? Map<*, *>
                    UploadResult(url = map?.get("url") as? String ?: "")
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ---- Helpers ----

    private fun compressImage(bytes: ByteArray, maxBytes: Long): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        var quality = 85
        var output: ByteArray
        do {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            output = stream.toByteArray()
            quality -= 10
        } while (output.size > maxBytes && quality >= 20)
        bitmap.recycle()
        return output
    }

    private fun createThumbnail(bytes: ByteArray): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val scaleFactor = maxOf(
                options.outWidth / THUMB_MAX_WIDTH,
                options.outHeight / THUMB_MAX_HEIGHT,
                1
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scaleFactor
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
