package com.oldchat.material.core.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Builds and parses MessagePayload JSON (v=2 format).
 * Mirrors MessagePayload.java from client-guide.md §3.1.
 *
 * v=2 format:
 * {
 *   "v": 2,
 *   "text": "hello",
 *   "media_kind": "image",
 *   "voice_text": "...",
 *   "quote": { "id": "...", "from_uid": "...", "from_name": "...", "type": "...", "text": "..." },
 *   "mentions": [{ "uid": "...", "name": "..." }],
 *   "forward_v2": { "title": "...", "items": [...] }
 * }
 *
 * v=0/1 fallback: treat message body as plain text.
 */
object MessagePayloadBuilder {

    private val gson = Gson()

    /**
     * Build a message body JSON string for sending.
     *
     * @param text plain text content
     * @param quote optional quoted message
     * @param mentions list of @mentions
     * @param mediaKind optional media type hint
     * @param forwardV2 optional forwarded messages bundle
     * @return JSON string of the payload
     */
    fun buildBody(
        text: String,
        quote: Quote? = null,
        mentions: List<Mention> = emptyList(),
        mediaKind: String? = null,
        forwardV2: ForwardBundle? = null
    ): String {
        val payload = MessagePayload(
            v = 2,
            text = text,
            mediaKind = mediaKind,
            quote = quote,
            mentions = mentions,
            forwardV2 = forwardV2
        )
        return gson.toJson(payload)
    }

    /**
     * Build a file resource body.
     * Format: {"v":2,"text":"file_name|file_size|download_url","media_kind":"resource"}
     */
    fun buildFileBody(fileName: String, fileSize: Long, downloadUrl: String): String {
        return buildBody(
            text = "$fileName|$fileSize|$downloadUrl",
            mediaKind = "resource"
        )
    }

    /**
     * Build a red packet body.
     */
    fun buildRedPacketBody(redPacketId: String, greeting: String): String {
        return buildBody(
            text = "$redPacketId|$greeting",
            mediaKind = "red_packet"
        )
    }

    /**
     * Parse message body into MessagePayload.
     * Handles v=2, v=1, v=0 and plain text fallback.
     */
    fun parse(body: String): MessagePayload {
        return try {
            val json = JsonParser.parseString(body)
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                val v = obj.get("v")?.asInt ?: 0
                if (v >= 1) {
                    gson.fromJson(body, MessagePayload::class.java)
                } else {
                    // v=0: treat as plain text
                    MessagePayload(v = 0, text = body)
                }
            } else {
                MessagePayload(v = 0, text = body)
            }
        } catch (e: Exception) {
            // Fallback: treat entire body as plain text
            MessagePayload(v = 0, text = body)
        }
    }

    /**
     * Extract displayable text from a message (handles all message types).
     */
    fun extractDisplayText(message: Message): String {
        return when (message.msgType) {
            "text" -> {
                val payload = message.cachedPayload ?: parse(message.body)
                payload.text.ifEmpty { message.body }
            }
            "image" -> "[图片]"
            "video" -> "[视频]"
            "voice" -> "[语音]"
            "emoji" -> "[表情]"
            "resource" -> {
                val parts = message.body.split("|")
                if (parts.size >= 2) "[文件] ${parts[0]}" else "[文件]"
            }
            "red_packet" -> "[红包]"
            "forward" -> {
                val payload = message.cachedPayload ?: parse(message.body)
                payload.forwardV2?.title ?: "[聊天记录]"
            }
            else -> message.body
        }
    }

    /**
     * Extract displayable text for recent chat list preview.
     */
    fun extractPreviewText(msgType: String, body: String): String {
        return when (msgType) {
            "text" -> {
                val payload = try {
                    parse(body)
                } catch (_: Exception) {
                    MessagePayload(text = body)
                }
                payload.text.ifEmpty { body }.take(100)
            }
            "image" -> "[图片]"
            "video" -> "[视频]"
            "voice" -> "[语音]"
            "emoji" -> "[表情]"
            "resource" -> {
                val parts = body.split("|")
                if (parts.size >= 2) "[文件] ${parts[0]}" else "[文件]"
            }
            "red_packet" -> "[红包]"
            "forward" -> "[聊天记录]"
            else -> body.take(100)
        }
    }

    /**
     * Build a Quote from a message.
     */
    fun buildQuote(message: Message): Quote {
        val displayText = extractDisplayText(message)
        return Quote(
            id = message.id,
            fromUid = message.fromUid,
            fromName = message.senderNcuid ?: message.fromUid,
            type = message.msgType,
            text = displayText,
            mediaKind = if (message.msgType == "image") "image" else null,
            thumbUrl = message.thumbUrl
        )
    }

    /**
     * Build a Quote from a group message (GroupMessage is a distinct model,
     * so it cannot reuse buildQuote(message: Message) directly).
     */
    fun buildGroupQuote(message: GroupMessage): Quote {
        val displayText = when (message.msgType) {
            "image" -> "[图片]"
            "video" -> "[视频]"
            "voice" -> "[语音]"
            "emoji" -> "[表情]"
            "resource" -> {
                val parts = message.body.split("|")
                if (parts.size >= 2) "[文件] ${parts[0]}" else "[文件]"
            }
            "red_packet" -> "[红包]"
            "forward" -> "[聊天记录]"
            else -> {
                val payload = message.cachedPayload ?: parse(message.body)
                payload.text.ifEmpty { message.body }
            }
        }
        val senderName = message.cachedSenderName ?: message.fromUid
        return Quote(
            id = message.id,
            fromUid = message.fromUid,
            fromName = senderName,
            type = message.msgType,
            text = displayText,
            mediaKind = if (message.msgType == "image") "image" else null,
            thumbUrl = message.thumbUrl
        )
    }
}
