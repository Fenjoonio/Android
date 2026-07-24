package io.fenjoon.app.notifications

/** One recent message selected for Android's native conversation notification. */
internal data class MessageLine(
    val senderId: String?,
    val sender: String,
    val body: String,
    val sentAtMillis: Long,
)

/** Pure content shared by initial posts and partial-read notification refreshes. */
internal data class ChatNotificationPresentation(
    val title: String,
    val isGroupConversation: Boolean,
    val messages: List<MessageLine>,
    val unreadCount: Int,
    val avatarUrl: String?,
) {
    companion object {
        private const val FALLBACK_CONVERSATION_NAME = "Conversation"
        private const val MAX_VISIBLE_MESSAGES = 6

        fun from(
            messages: List<ChatNotificationHistoryStore.ChatNotificationEntry>,
        ): ChatNotificationPresentation {
            require(messages.isNotEmpty()) { "Chat notification requires at least one message" }
            val recent = messages.takeLast(MAX_VISIBLE_MESSAGES)
            val latest = recent.last()
            val isGroup = latest.conversationType == NotificationPayload.ConversationType.GROUP ||
                latest.conversationType == NotificationPayload.ConversationType.CHANNEL
            val latestSender = latest.sender.ifBlank { FALLBACK_CONVERSATION_NAME }
            val title = if (isGroup) {
                latest.conversationTitle?.takeIf(String::isNotBlank) ?: latestSender
            } else {
                latestSender
            }
            val senderAvatarUrl = recent.asReversed().firstNotNullOfOrNull { message ->
                message.senderImageUrl?.takeIf(String::isNotBlank)
            }
            val conversationAvatarUrl = recent.asReversed().firstNotNullOfOrNull { message ->
                message.conversationImageUrl?.takeIf(String::isNotBlank)
            }
            val avatarUrl = if (isGroup) {
                conversationAvatarUrl ?: senderAvatarUrl
            } else {
                senderAvatarUrl
            }
            val lines = recent.map { message ->
                MessageLine(
                    senderId = message.senderId,
                    sender = message.sender.ifBlank { title },
                    body = message.body,
                    sentAtMillis = message.sentAtMillis,
                )
            }
            return ChatNotificationPresentation(
                title = title,
                isGroupConversation = isGroup,
                messages = lines,
                unreadCount = latest.unreadCount.coerceAtLeast(0),
                avatarUrl = avatarUrl,
            )
        }
    }
}
