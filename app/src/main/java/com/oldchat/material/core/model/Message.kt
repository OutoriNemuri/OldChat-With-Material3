package com.oldchat.material.core.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

/**
 * Direct message model. Mirrors original Message.java from client-guide.md §3.1.
 */
@Serializable
data class Message(
    val id: String = "",                    // Server ID or "local_*" temp ID
    @SerializedName("thread_id")
    val threadId: String = "",             // Conversation thread ID
    @SerializedName("from_uid")
    val fromUid: String = "",
    @SerializedName("peer_uid")
    val peerUid: String = "",               // 对方 uid（单聊会话里，自己发的消息 from_uid=自己，peer_uid=对方）
    val body: String = "",                  // Plain text or MessagePayload JSON
    @SerializedName("msg_type")
    val msgType: String = "text",           // text/image/video/voice/emoji/resource/red_packet/file/forward
    @SerializedName("media_url")
    val mediaUrl: String? = null,
    @SerializedName("thumb_url")
    val thumbUrl: String? = null,
    @SerializedName("duration_ms")
    val durationMs: Int = 0,               // Voice/video duration
    @SerializedName("burn_after_seconds")
    val burnAfterSeconds: Int = 0,
    @SerializedName("burn_start_at")
    val burnStartAt: Long = 0,
    @SerializedName("created_at")
    val createdAt: Long = 0,               // Unix seconds
    @SerializedName("sort_seq")
    val sortSeq: Long = 0,                 // SQLite rowid for same-second ordering
    val status: Int = STATUS_NONE,         // SENT/DELIVERED/READ
    @SerializedName("delivered_at")
    val deliveredAt: Long = 0,             // 送达时间（Unix 秒，0=未送达）
    @SerializedName("read_at")
    val readAt: Long = 0,                  // 已读时间（Unix 秒，0=未读）
    @SerializedName("is_local_pending")
    val isLocalPending: Boolean = false,
    @SerializedName("is_local_failed")
    val isLocalFailed: Boolean = false,
    @SerializedName("local_request_id")
    val localRequestId: String? = null,
    @SerializedName("local_preview_uri")
    val localPreviewUri: String? = null,
    @SerializedName("local_progress")
    val localProgress: Int = -1,           // Upload progress 0-100, -1 = no progress
    @SerializedName("recall_edit_type")
    val recallEditType: String? = null,
    @SerializedName("recall_edit_text")
    val recallEditText: String? = null,
    @SerializedName("from_ncuid")
    val senderNcuid: String? = null,
    // Parsed payload (transient, not serialized to cache)
    val cachedPayload: MessagePayload? = null
) {
    companion object {
        const val STATUS_NONE = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_READ = 3
    }
}

/**
 * Message payload v2 format. Mirrors MessagePayload.java from client-guide.md §3.1.
 */
@Serializable
data class MessagePayload(
    val v: Int = 2,
    val text: String = "",
    @SerializedName("media_kind")
    val mediaKind: String? = null,
    @SerializedName("voice_text")
    val voiceText: String? = null,
    val quote: Quote? = null,
    val mentions: List<Mention> = emptyList(),
    @SerializedName("forward_v2")
    val forwardV2: ForwardBundle? = null
)

@Serializable
data class Quote(
    val id: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val type: String = "",
    val text: String = "",
    val mediaKind: String? = null,
    val thumbUrl: String? = null
)

@Serializable
data class Mention(
    val uid: String = "",
    val name: String = ""
)

@Serializable
data class ForwardBundle(
    val title: String = "",
    val items: List<ForwardItem> = emptyList()
)

@Serializable
data class ForwardItem(
    val sourceMessageId: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val fromAvatar: String? = null,
    val type: String = "",
    val mediaKind: String? = null,
    val text: String = ""
)
