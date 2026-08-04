package io.fenjoon.app

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.microsoft.clarity.Clarity
import com.microsoft.clarity.ClarityConfig
import com.microsoft.clarity.models.LogLevel
import io.fenjoon.app.notifications.ChatNotificationCoordinator
import io.fenjoon.app.notifications.NotificationChannels
import io.fenjoon.app.notifications.TokenStore

/**
 * App-wide setup. Registers the notification channels once at process start and warms the
 * FCM token so it's cached (in [TokenStore]) and ready to hand to the web layer on the next
 * page load — see the token bridge in [MainActivity].
 */
class FenjoonApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val clarityProjectId = BuildConfig.CLARITY_PROJECT_ID
        if (clarityProjectId.isNotBlank()) {
            val clarityConfig = ClarityConfig(
                projectId = clarityProjectId,
                logLevel = if (BuildConfig.DEBUG) LogLevel.Verbose else LogLevel.None,
            )
            Clarity.initialize(applicationContext, clarityConfig)
        }

        NotificationChannels.createAll(this)
        Thread(
            { ChatNotificationCoordinator.reconcile(applicationContext) },
            "ChatNotificationReconcile",
        ).start()

        if (BuildConfig.DEBUG) {
            AppSignatureHelper(this).getAppSignatures()
        }

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> TokenStore(this).save(token) }
            .addOnFailureListener { e -> Log.w(TAG, "Failed to fetch FCM token", e) }
    }

    private companion object {
        const val TAG = "FenjoonApp"
    }
}
