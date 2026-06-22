package io.fenjoon.app.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.math.absoluteValue

class FenjoonFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val title = data[KEY_TITLE] ?: remoteMessage.notification?.title
        val body = data[KEY_BODY] ?: remoteMessage.notification?.body
        val deepLink = data[KEY_DEEP_LINK] ?: data[KEY_URL]
        val urgent = data[KEY_URGENT]?.toBooleanStrictOrNull() ?: false
        val notificationId = data[KEY_NOTIFICATION_ID]
            ?.toIntOrNull()
            ?: (remoteMessage.messageId ?: deepLink ?: body ?: title ?: System.currentTimeMillis().toString())
                .hashCode()
                .absoluteValue

        FenjoonNotificationHelper.showNotification(
            context = this,
            title = title,
            body = body,
            deepLink = deepLink,
            notificationId = notificationId,
            urgent = urgent
        )
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed: ${token.redactedForLog()}")
        // TODO: Send this token to the Fenjoon backend/proxy over HTTPS when the API is ready.
    }

    private fun String.redactedForLog(): String {
        if (length <= 12) return "***"
        return "${take(6)}...${takeLast(6)}"
    }

    private companion object {
        private const val TAG = "FenjoonMessaging"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_DEEP_LINK = "deep_link"
        private const val KEY_URL = "url"
        private const val KEY_URGENT = "urgent"
        private const val KEY_NOTIFICATION_ID = "notification_id"
    }
}
