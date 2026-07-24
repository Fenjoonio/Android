package io.fenjoon.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages. We rely on **data-only** messages so this runs for both foreground and
 * background delivery and we fully control channel/style/actions via [Notifier]. A `notification`
 * block would be auto-displayed by the system when backgrounded, bypassing all of that.
 */
class FenjoonMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Cache it; the web layer (which knows the user) registers it via the JS bridge on the
        // next page load. No native user identity exists to map it here.
        TokenStore(this).save(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return

        val payload = NotificationPayload.from(data)
        val conversationId = payload.conversationId

        // Read receipts are control messages, not user-visible notifications. Prune only through
        // the server's inclusive boundary so a newer push racing this receipt remains visible.
        if (payload.isChatReadControl) {
            if (conversationId != null) {
                val boundary = payload.messageId
                if (boundary == null) {
                    Notifier.cancelConversation(this, conversationId)
                } else {
                    Notifier.applyReadThrough(this, conversationId, boundary)
                }
            }
            return
        }
        if (payload.type == NotificationPayload.Type.CHAT &&
            conversationId != null &&
            NotificationPresentationState.shouldSuppressChat(conversationId)
        ) {
            Notifier.cancelConversation(this, conversationId)
            return
        }

        Notifier.show(this, payload)
    }

}
