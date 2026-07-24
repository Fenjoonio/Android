package io.fenjoon.app.notifications

/**
 * Process-local presentation state shared by the activity, WebView bridge, and FCM service.
 * Access is synchronized because those entry points run on different threads.
 */
object NotificationPresentationState {
    private val lock = Any()

    private var activityForeground = false
    private var activeConversationId: String? = null

    fun reset() = synchronized(lock) {
        activityForeground = false
        activeConversationId = null
    }

    fun setActivityForeground(foreground: Boolean) = synchronized(lock) {
        activityForeground = foreground
    }

    fun setActiveConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        synchronized(lock) {
            activeConversationId = conversationId
        }
    }

    /** Clears only the conversation that originally registered itself as active. */
    fun clearActiveConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        synchronized(lock) {
            if (activeConversationId == conversationId) {
                activeConversationId = null
            }
        }
    }

    fun shouldSuppressChat(conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        return synchronized(lock) {
            activityForeground && activeConversationId == conversationId
        }
    }
}
