package io.fenjoon.app.notifications

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A parsed FCM **data** message. The backend sends data-only messages (never a `notification`
 * block) so [FenjoonMessagingService] is always invoked and can pick the channel/style/actions
 * itself via [Notifier]. Every FCM data value arrives as a String, so parsing is just typed
 * lookups with blank-guards.
 *
 * The canonical field names here are the contract with the backend — keep them in sync with
 * what the server emits in `internal/services/notification.go` (`buildPushData`) and the chat
 * push path. The schema is:
 *
 *   Common: title, body, type, action, targetType, targetId, chapterId, blockId, url,
 *           notificationId
 *   Chat:   conversationId, messageId, senderId, sentAt, tag, sender, replyUrl, readUrl,
 *           unreadCount, conversationType, conversationTitle, senderImageUrl,
 *           conversationImageUrl
 *   Control: type=chat.read, conversationId, upToMessageId (the inclusive read boundary)
 *
 * Chat identity is always derived from `conversationId`. This also migrates older chat payloads
 * that omitted `tag` to the same notification slot used by the current schema. `chat.read` is a
 * silent control message, represented by [isChatReadControl], and must never be displayed.
 */
data class NotificationPayload(
    val type: Type,
    val title: String,
    val body: String,
    val url: String?,
    val tag: String?,
    /** Legacy/common image field retained for non-chat and old chat payloads. */
    val imageUrl: String?,
    val sender: String?,
    val conversationId: String?,
    val messageId: String?,
    val senderId: String?,
    val sentAt: String?,
    /** Parsed server timestamp. Null means the timestamp was absent or malformed. */
    val sentAtMillis: Long?,
    val replyUrl: String?,
    val readUrl: String?,
    /** Nonnegative server unread count; absent/invalid values fall back to zero. */
    val unreadCount: Int,
    val conversationType: ConversationType?,
    val conversationTitle: String?,
    val senderImageUrl: String?,
    val conversationImageUrl: String?,
    val action: String?,
    val targetType: String?,
    val targetId: String?,
    val chapterId: String?,
    val blockId: String?,
    val notificationId: Int,
) {
    enum class Type(val key: String, val channelId: String) {
        CHAT("chat", NotificationChannels.CHAT),
        /** Silent control payload; shares the chat channel identity but is never displayed. */
        CHAT_READ("chat.read", NotificationChannels.CHAT),
        SOCIAL("social", NotificationChannels.SOCIAL_ALERTS),
        REMINDER("reminder", NotificationChannels.REMINDERS),
        URGENT("urgent", NotificationChannels.URGENT),
        DEFAULT("default", NotificationChannels.GENERAL_ALERTS);

        companion object {
            fun fromKey(key: String?): Type =
                entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DEFAULT

            /**
             * Maps a backend `action` (e.g. "story.liked", "novel.moderation") to a notification
             * Type for channel/style selection. Returns null when there's no mapping, so the
             * caller can fall back to `data["type"]` or DEFAULT.
             */
            fun fromAction(action: String?): Type? = when (action?.lowercase()?.trim()) {
                "novel.moderation" -> URGENT
                "story.created", "story.liked",
                "comment.created", "comment.liked", "comment.reply",
                "mention", "follow", "note" -> SOCIAL
                "achievement", "invitation" -> DEFAULT
                else -> null
            }
        }
    }

    val isChatReadControl: Boolean
        get() = type == Type.CHAT_READ

    enum class ConversationType(val key: String) {
        DIRECT("direct"),
        GROUP("group"),
        CHANNEL("channel");

        companion object {
            fun fromKey(key: String?): ConversationType? =
                entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
        }
    }

    companion object {
        private val RFC3339 = Regex(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})$",
            RegexOption.IGNORE_CASE,
        )

        fun from(data: Map<String, String>): NotificationPayload {
            val action = data.nonBlank("action")
            val suppliedTag = data.nonBlank("tag")
            val conversationId = data.nonBlank("conversationId")
            val url = data.nonBlank("url")
            val rawType = data.nonBlank("type")
            val explicitType = rawType?.let { Type.fromKey(it).takeIf { type -> type != Type.DEFAULT } }
            val type = explicitType ?: Type.fromAction(action) ?: Type.DEFAULT
            val isChatType = type == Type.CHAT || type == Type.CHAT_READ
            val chatIdentity = conversationId
                ?.takeIf { isChatType }
                ?.let(ChatNotificationIdentity::canonical)
            val tag = chatIdentity?.tag ?: suppliedTag
            val stableKey = tag ?: conversationId ?: url ?: action ?: type.key

            val imageUrl = data.nonBlank("imageUrl")
            val sentAt = data.nonBlank("sentAt")
                ?: data.nonBlank("serverTimestamp")
                ?: data.nonBlank("timestamp")
            val senderImageUrl = data.nonBlank("senderImageUrl")
                ?: data.nonBlank("senderImage")
                ?: imageUrl.takeIf { isChatType }
            val conversationImageUrl = data.nonBlank("conversationImageUrl")
                ?: data.nonBlank("conversationImage")

            return NotificationPayload(
                type = type,
                title = data["title"].orEmpty(),
                body = data["body"].orEmpty(),
                url = url,
                tag = tag,
                imageUrl = imageUrl,
                sender = data.nonBlank("sender"),
                conversationId = conversationId,
                messageId = if (type == Type.CHAT_READ) {
                    data.nonBlank("upToMessageId")
                        ?: data.nonBlank("messageId")
                        ?: data.nonBlank("readThroughMessageId")
                } else {
                    data.nonBlank("messageId")
                },
                senderId = data.nonBlank("senderId"),
                sentAt = sentAt,
                sentAtMillis = sentAt?.let(::parseServerTimestampMillis),
                replyUrl = data.nonBlank("replyUrl"),
                readUrl = data.nonBlank("readUrl"),
                unreadCount = data.nonBlank("unreadCount")
                    ?.toIntOrNull()
                    ?.takeIf { it >= 0 }
                    ?: 0,
                conversationType = ConversationType.fromKey(data.nonBlank("conversationType")),
                conversationTitle = data.nonBlank("conversationTitle"),
                senderImageUrl = senderImageUrl,
                conversationImageUrl = conversationImageUrl,
                action = action,
                targetType = data.nonBlank("targetType"),
                targetId = data.nonBlank("targetId"),
                chapterId = data.nonBlank("chapterId"),
                blockId = data.nonBlank("blockId"),
                // Stable id so a repeat push (same conversation/tag/url/action) updates rather
                // than stacks. Chat, including silent read controls, uses its canonical identity.
                notificationId = chatIdentity?.id ?: stableKey.hashCode(),
            )
        }

        /**
         * Parses RFC3339/RFC3339Nano without java.time, which keeps this safe on the app's
         * minSdk 24 without requiring core-library desugaring. Fractions beyond milliseconds are
         * truncated because Android notification timestamps have millisecond precision.
         */
        internal fun parseServerTimestampMillis(value: String): Long? {
            val trimmed = value.trim()
            trimmed.toLongOrNull()?.let { numeric ->
                // Accept epoch seconds as a compatibility fallback; current backend emits RFC3339Nano.
                return if (trimmed.length <= 10) numeric * 1_000L else numeric
            }

            val match = RFC3339.matchEntire(trimmed) ?: return null
            val base = match.groupValues[1]
            val fraction = match.groupValues[2]
                .padEnd(3, '0')
                .take(3)
                .ifEmpty { "000" }
            val rawZone = match.groupValues[3]
            val zone = if (rawZone.equals("Z", ignoreCase = true)) {
                "+0000"
            } else {
                rawZone.removeRange(3, 4)
            }
            val normalized = "$base.$fraction$zone"
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
                isLenient = false
            }
            val position = ParsePosition(0)
            val parsed = parser.parse(normalized, position) ?: return null
            return parsed.time.takeIf { position.index == normalized.length }
        }

        private fun Map<String, String>.nonBlank(key: String): String? =
            this[key]?.trim()?.takeIf { it.isNotEmpty() }
    }
}
