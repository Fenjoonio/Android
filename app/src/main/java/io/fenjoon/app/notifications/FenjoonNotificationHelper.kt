package io.fenjoon.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.fenjoon.app.MainActivity
import io.fenjoon.app.R

object FenjoonNotificationHelper {
    private const val DEFAULT_CHANNEL_ID = "fenjoon_default_notifications"
    private const val URGENT_CHANNEL_ID = "fenjoon_urgent_notifications"
    private const val SILENT_CHANNEL_ID = "silent"
    private const val DEFAULT_URL = "https://app.fenjoon.io?utm_source=push"
    private const val FENJOON_HOST = "app.fenjoon.io"
    private const val FENJOON_SCHEME = "fenjoon"
    private val urgentVibrationPattern = longArrayOf(0, 500, 250, 500)

    fun showNotification(
        context: Context,
        title: String?,
        body: String?,
        deepLink: String?,
        notificationId: Int = System.currentTimeMillis().toInt(),
        urgent: Boolean = false
    ) {
        if (!canPostNotifications(context)) return

        createDefaultChannel(context)

        val channelId = if (urgent) URGENT_CHANNEL_ID else DEFAULT_CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.takeUnless { it.isNullOrBlank() }
                ?: context.getString(R.string.notification_default_title))
            .setContentText(body.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
            .setContentIntent(createContentIntent(context, deepLink))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                if (urgent) {
                    setVibrate(urgentVibrationPattern)
                    setDefaults(NotificationCompat.DEFAULT_LIGHTS)
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun showSilentReminder(
        context: Context,
        title: String,
        notificationId: Int = 1610
    ) {
        if (!canPostNotifications(context)) return

        createDefaultChannel(context)

        val notification = NotificationCompat.Builder(context, SILENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentIntent(createContentIntent(context, DEFAULT_URL))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun createDefaultChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        if (notificationManager.getNotificationChannel(DEFAULT_CHANNEL_ID) == null) {
            val defaultChannel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(defaultChannel)
        }

        if (notificationManager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
            val silentChannel = NotificationChannel(
                SILENT_CHANNEL_ID,
                context.getString(R.string.silent_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.silent_notification_channel_description)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(silentChannel)
        }

        if (notificationManager.getNotificationChannel(URGENT_CHANNEL_ID) == null) {
            val urgentChannel = NotificationChannel(
                URGENT_CHANNEL_ID,
                context.getString(R.string.urgent_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.urgent_notification_channel_description)
                enableVibration(true)
                vibrationPattern = urgentVibrationPattern
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(urgentChannel)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createContentIntent(context: Context, deepLink: String?): PendingIntent {
        val targetUri = deepLink.toFenjoonUri() ?: Uri.parse(DEFAULT_URL)
        val intent = Intent(Intent.ACTION_VIEW, targetUri, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            targetUri.toString().hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun String?.toFenjoonUri(): Uri? {
        if (isNullOrBlank()) return null

        val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
        return when {
            uri.scheme == "https" && uri.host == FENJOON_HOST -> uri
            uri.scheme == FENJOON_SCHEME -> uri
            else -> null
        }
    }
}
