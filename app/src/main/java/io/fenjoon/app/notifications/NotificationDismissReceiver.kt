package io.fenjoon.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconciles native chat grouping after a child or summary is swiped away. */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> intent.getStringExtra(NotificationActionExtras.CONVERSATION_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { ChatNotificationCoordinator.childDismissed(context, it) }
            ACTION_DISMISS_SUMMARY -> ChatNotificationCoordinator.summaryDismissed(
                context,
                intent.getStringArrayListExtra(EXTRA_REPRESENTED_CONVERSATION_IDS).orEmpty(),
            )
        }
    }

    companion object {
        const val ACTION_DISMISS = "io.fenjoon.app.notifications.action.DISMISS"
        const val ACTION_DISMISS_SUMMARY =
            "io.fenjoon.app.notifications.action.DISMISS_CHAT_SUMMARY"
        const val EXTRA_REPRESENTED_CONVERSATION_IDS =
            "extra_represented_conversation_ids"

        internal fun pendingIntent(
            context: Context,
            requestCode: Int,
            conversationId: String,
        ): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(NotificationActionExtras.CONVERSATION_ID, conversationId)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        internal fun summaryPendingIntent(
            context: Context,
            requestCode: Int,
            conversationIds: Collection<String>,
        ): PendingIntent {
            val snapshot = ArrayList(
                conversationIds
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .sorted()
                    .toList(),
            )
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISS_SUMMARY
                putStringArrayListExtra(EXTRA_REPRESENTED_CONVERSATION_IDS, snapshot)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
