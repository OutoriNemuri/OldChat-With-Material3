package com.oldchat.material.core.model

import kotlinx.serialization.Serializable

/**
 * User/friend model.
 */
@Serializable
data class User(
    val uid: String = "",
    val nickname: String = "",
    val avatarUrl: String? = null,
    val title: String? = null,
    val presenceStatus: String = "offline",   // online/offline/away
    val bio: String? = null,
    val creditScore: Int = 0,
    val balance: Double = 0.0
)

/**
 * Recent chat item for the home screen combined list (§7.1).
 */
@Serializable
data class RecentChatItem(
    val type: String = "direct",               // direct/group/system
    val chatId: String = "",                   // uid or groupId
    val name: String = "",
    val avatarUrl: String? = null,
    val lastMessage: String = "",
    val lastMessageType: String = "text",
    val lastTime: Long = 0,                    // Unix seconds
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val presenceStatus: String = "offline"
)

/**
 * Auth response from /auth/login or /auth/refresh.
 */
@Serializable
data class AuthResponse(
    val accessToken: String = "",
    val refreshToken: String = "",
    val userId: String = "",
    val uid: String = ""
)

/**
 * Handshake response from /auth/handshake.
 */
@Serializable
data class HandshakeResponse(
    val sessionId: String = "",
    val serverPublicKey: String = ""
)

/**
 * Standard API response wrapper.
 */
@Serializable
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null
)
