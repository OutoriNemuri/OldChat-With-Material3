package com.oldchat.material.core.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

/**
 * Group model. Mirrors original Group.java from client-guide.md §4.1.
 */
@Serializable
data class Group(
    val id: String = "",
    val name: String = "",
    @SerializedName("avatar_url")
    val avatarUrl: String? = null,
    val role: String = "",           // owner/admin/member
    @SerializedName("owner_uid")
    val ownerUid: String = "",
    @SerializedName("member_count")
    val memberCount: Int = 0,
    val description: String? = null
)

/**
 * GroupMessage model. Mirrors original GroupMessage.java from client-guide.md §4.1.
 * Extends Message with group-specific fields.
 */
@Serializable
data class GroupMessage(
    val id: String = "",
    @SerializedName("group_id")
    val groupId: String = "",
    @SerializedName("group_seq")
    val groupSeq: Long = 0,          // Reliable ordering within group
    @SerializedName("from_uid")
    val fromUid: String = "",
    val body: String = "",
    @SerializedName("msg_type")
    val msgType: String = "text",
    @SerializedName("media_url")
    val mediaUrl: String? = null,
    @SerializedName("thumb_url")
    val thumbUrl: String? = null,
    @SerializedName("duration_ms")
    val durationMs: Int = 0,
    @SerializedName("burn_after_seconds")
    val burnAfterSeconds: Int = 0,
    @SerializedName("burn_start_at")
    val burnStartAt: Long = 0,
    @SerializedName("created_at")
    val createdAt: Long = 0,
    @SerializedName("sort_seq")
    val sortSeq: Long = 0,
    val status: Int = 0,
    @SerializedName("read_count")
    val readCount: Int = 0,
    // 注意：服务端群聊消息接口（/groups/messages、/v2、/after）实测【不返回】delivered_at/read_at。
    // 保留以下字段以备未来服务端支持送达回执；当前 deliveredAt 恒为 0，UI 上不会显示送达对号。
    @SerializedName("delivered_at")
    val deliveredAt: Long = 0,
    @SerializedName("read_at")
    val readAt: Long = 0,
    @SerializedName("is_local_pending")
    val isLocalPending: Boolean = false,
    @SerializedName("is_local_failed")
    val isLocalFailed: Boolean = false,
    @SerializedName("local_request_id")
    val localRequestId: String? = null,
    @SerializedName("local_preview_uri")
    val localPreviewUri: String? = null,
    @SerializedName("local_progress")
    val localProgress: Int = -1,
    // Transient cache fields
    val cachedPayload: MessagePayload? = null,
    val cachedSenderName: String? = null,
    val cachedSenderTitle: String? = null,
    val cachedSenderRole: String? = null,
    val cachedAvatarUrl: String? = null
)

/**
 * Group sync watermark for reliable sync (§4.2).
 */
data class GroupSyncWatermark(
    val groupSeq: Long = 0,
    val anchorMessageId: String = ""
)
