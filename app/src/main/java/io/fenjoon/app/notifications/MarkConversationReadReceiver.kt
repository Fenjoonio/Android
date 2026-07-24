package io.fenjoon.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Handles the standalone native "mark as read" action. Notification taps open MainActivity. */
class MarkConversationReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_READ) return
        val conversationId = intent.getStringExtra(NotificationActionExtras.CONVERSATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val messageId = intent.getStringExtra(NotificationActionExtras.MESSAGE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val readUrl = intent.getStringExtra(NotificationActionExtras.READ_URL)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                val markedRead = try {
                    NotificationActionHttpClient().postRead(readUrl, conversationId, messageId)
                } catch (exception: Exception) {
                    Log.w(TAG, "Mark-read request failed", exception)
                    false
                }
                if (markedRead) {
                    Notifier.applyReadThrough(appContext, conversationId, messageId)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_MARK_READ = "io.fenjoon.app.notifications.action.MARK_READ"
        private const val TAG = "MarkConversationRead"
    }
}

/** Shared intent keys for all native notification actions. */
object NotificationActionExtras {
    const val NOTIFICATION_ID = "extra_notification_id"
    const val TAG = "extra_tag"
    const val CONVERSATION_ID = "extra_conversation_id"
    const val MESSAGE_ID = "extra_message_id"
    const val REPLY_URL = "extra_reply_url"
    const val READ_URL = "extra_read_url"
    const val SENDER = "extra_sender"
    const val TITLE = "extra_title"
    const val LINK = "extra_link"
}
