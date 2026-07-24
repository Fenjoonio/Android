package io.fenjoon.app.notifications

/** The Android notification identity used for one chat conversation. */
data class ChatNotificationIdentity(
    val tag: String?,
    val id: Int,
) {
    companion object {
        private const val TAG_PREFIX = "chat:"
        private const val SUMMARY_TAG = "chat:__group_summary__"

        /** Fixed native group identity shared by every canonical child when two or more are active. */
        const val GROUP_KEY = "io.fenjoon.app.notifications.CHAT_CONVERSATIONS"

        /** The fixed group summary must never be parsed as a conversation child. */
        val summary = ChatNotificationIdentity(tag = SUMMARY_TAG, id = SUMMARY_TAG.hashCode())

        /** Current identity shared by new and legacy chat payloads. */
        fun canonical(conversationId: String): ChatNotificationIdentity {
            require(conversationId.isNotBlank()) { "conversationId must not be blank" }
            val tag = "$TAG_PREFIX$conversationId"
            return ChatNotificationIdentity(tag = tag, id = tag.hashCode())
        }

        /** Returns the canonical conversation id only when both tag and stable id match. */
        fun parseCanonical(tag: String?, id: Int): String? {
            if (tag == null || tag == SUMMARY_TAG || !tag.startsWith(TAG_PREFIX)) return null
            val conversationId = tag.removePrefix(TAG_PREFIX).takeIf(String::isNotBlank) ?: return null
            return conversationId.takeIf { canonical(it).id == id }
        }

        /** Identity used before chat notifications received a canonical tag. */
        fun legacy(conversationId: String): ChatNotificationIdentity {
            require(conversationId.isNotBlank()) { "conversationId must not be blank" }
            return ChatNotificationIdentity(tag = null, id = conversationId.hashCode())
        }
    }
}
