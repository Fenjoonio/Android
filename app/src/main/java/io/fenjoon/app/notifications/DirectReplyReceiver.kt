package io.fenjoon.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/** Sends an inline notification reply through the authenticated Fenjoon HTTP fallback. */
class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val replyUrl = intent.getStringExtra(EXTRA_REPLY_URL)?.takeIf { it.isNotBlank() } ?: return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val history = ChatNotificationHistoryStore(context.applicationContext)
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: history.get(conversationId).lastOrNull()?.messageId
            ?: return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                val sent = try {
                    NotificationActionHttpClient().postMessage(
                        rawUrl = replyUrl,
                        conversationId = conversationId,
                        message = replyText,
                        readThroughMessageId = messageId,
                    )
                } catch (exception: Exception) {
                    Log.w(TAG, "Reply send failed", exception)
                    false
                }

                if (sent) {
                    applyReadThrough(appContext, conversationId, messageId)
                } else {
                    rebuildAfterFailure(appContext, intent, conversationId, history)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun applyReadThrough(context: Context, conversationId: String, messageId: String) {
        Notifier.applyReadThrough(context, conversationId, messageId)
    }

    private fun rebuildAfterFailure(
        context: Context,
        sourceIntent: Intent,
        conversationId: String,
        history: ChatNotificationHistoryStore,
    ) {
        val retained = history.get(conversationId)
        if (retained.isNotEmpty()) {
            Notifier.refreshConversation(context, conversationId, retained)
            return
        }

        Notifier.showReplyFailure(
            context = context,
            conversationId = conversationId,
            messageId = sourceIntent.getStringExtra(EXTRA_MESSAGE_ID),
            readUrl = sourceIntent.getStringExtra(EXTRA_READ_URL),
            sender = sourceIntent.getStringExtra(EXTRA_SENDER),
            title = sourceIntent.getStringExtra(EXTRA_TITLE),
            link = sourceIntent.getStringExtra(EXTRA_LINK),
        )
    }

    companion object {
        const val KEY_REPLY_TEXT = "key_reply_text"
        const val EXTRA_NOTIFICATION_ID = NotificationActionExtras.NOTIFICATION_ID
        const val EXTRA_TAG = NotificationActionExtras.TAG
        const val EXTRA_CONVERSATION_ID = NotificationActionExtras.CONVERSATION_ID
        const val EXTRA_MESSAGE_ID = NotificationActionExtras.MESSAGE_ID
        const val EXTRA_REPLY_URL = NotificationActionExtras.REPLY_URL
        const val EXTRA_READ_URL = NotificationActionExtras.READ_URL
        const val EXTRA_SENDER = NotificationActionExtras.SENDER
        const val EXTRA_TITLE = NotificationActionExtras.TITLE
        const val EXTRA_LINK = NotificationActionExtras.LINK

        private const val TAG = "DirectReplyReceiver"
    }
}
