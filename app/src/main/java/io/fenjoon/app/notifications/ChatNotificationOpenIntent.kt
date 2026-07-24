package io.fenjoon.app.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.fenjoon.app.MainActivity

/** Direct activity intent for chat taps; avoids Android 12+'s notification trampoline ban. */
object ChatNotificationOpenIntent {
    const val ACTION_OPEN_CHAT = "io.fenjoon.app.notifications.action.OPEN_CHAT"
    const val DEFAULT_LINK = "https://app.fenjoon.io"
    private const val EXTRA_OPEN_TOKEN = "extra_notification_open_token"

    fun pendingIntent(
        context: Context,
        requestCode: Int,
        conversationId: String,
        messageId: String?,
        readUrl: String?,
        notificationId: Int,
        tag: String?,
        link: String?,
    ): PendingIntent {
        val intent = activityIntent(
            context = context,
            conversationId = conversationId,
            messageId = messageId,
            readUrl = readUrl,
            notificationId = notificationId,
            tag = tag,
            link = link,
        )
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun activityIntent(
        context: Context,
        conversationId: String,
        messageId: String?,
        readUrl: String?,
        notificationId: Int,
        tag: String?,
        link: String?,
    ): Intent {
        val safeLink = safeWebLink(link) ?: DEFAULT_LINK
        val safeReadUrl = readUrl?.takeIf {
            NotificationActionHttpClient.validate(
                rawUrl = it,
                action = NotificationActionHttpClient.Action.READ,
                expectedConversationId = conversationId,
            ) != null
        }
        return Intent(ACTION_OPEN_CHAT, Uri.parse(safeLink), context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(NotificationActionExtras.CONVERSATION_ID, conversationId)
            putExtra(NotificationActionExtras.MESSAGE_ID, messageId)
            putExtra(NotificationActionExtras.READ_URL, safeReadUrl)
            putExtra(NotificationActionExtras.NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionExtras.TAG, tag)
            putExtra(NotificationActionExtras.LINK, safeLink)
            putExtra(EXTRA_OPEN_TOKEN, "$conversationId|${messageId.orEmpty()}|$notificationId|$safeLink")
        }
    }

    fun openToken(intent: Intent): String? =
        intent.getStringExtra(EXTRA_OPEN_TOKEN)?.takeIf(String::isNotBlank)

    fun safeWebLink(rawLink: String?): String? {
        if (rawLink.isNullOrBlank()) return null
        val uri = try {
            Uri.parse(rawLink)
        } catch (_: RuntimeException) {
            return null
        }
        return uri.toString().takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("app.fenjoon.io", ignoreCase = true) &&
                uri.userInfo == null &&
                uri.port == -1
        }
    }
}
