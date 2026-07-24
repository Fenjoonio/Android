package io.fenjoon.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import io.fenjoon.app.notifications.ChatNotificationHistoryStore
import io.fenjoon.app.notifications.NotificationPresentationState
import io.fenjoon.app.notifications.Notifier
import io.fenjoon.app.notifications.TokenStore

/**
 * JS bridge exposed as `window.AndroidNotifications`. The web app owns the user session, so it's
 * responsible for reading the FCM token and registering it (token↔user) with the backend, and
 * for deciding when to prompt for notification permission. This bridge just gives it the hooks.
 *
 * `@JavascriptInterface` methods run on the WebView's JavaBridge thread; [requestPermission] hops
 * to the main thread because launching a permission request must happen there.
 */
class NotificationBridge(
    context: Context,
    private val onRequestPermission: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenStore = TokenStore(appContext)

    /** Current FCM token, or "" if not fetched yet (web can retry, or wait for onFcmToken). */
    @JavascriptInterface
    fun getToken(): String = tokenStore.get().orEmpty()

    /** Whether the user has notifications enabled for the app (channel-agnostic). */
    @JavascriptInterface
    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    /** Triggers the native POST_NOTIFICATIONS prompt (Android 13+); no-op below. */
    @JavascriptInterface
    fun requestPermission() {
        mainHandler.post { onRequestPermission() }
    }

    /** Marks the conversation currently visible in the web app. */
    @JavascriptInterface
    fun setActiveConversation(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        NotificationPresentationState.setActiveConversation(conversationId)
        Notifier.cancelConversation(appContext, conversationId)
    }

    /** Clears the conversation only if it is still the active one. */
    @JavascriptInterface
    fun clearActiveConversation(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        NotificationPresentationState.clearActiveConversation(conversationId)
    }

    /** Removes any displayed notification for the supplied conversation. */
    @JavascriptInterface
    fun cancelConversationNotification(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        Notifier.cancelConversation(appContext, conversationId)
    }

    /** Invalidate the token on logout so this device stops receiving the old user's pushes. */
    @JavascriptInterface
    fun clearToken() {
        FirebaseMessaging.getInstance().deleteToken()
        tokenStore.clear()
        ChatNotificationHistoryStore(appContext).clearAll()
        NotificationManagerCompat.from(appContext).cancelAll()
        NotificationPresentationState.reset()
    }
}
