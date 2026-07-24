package io.fenjoon.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import io.fenjoon.app.R

/**
 * Central registry of notification channels. Channels are user-facing categories in Android
 * settings, so each notification type gets its own. The [id] constants double as routing keys:
 * [NotificationPayload.Type.channelId] maps a push `type` to one of these channels.
 *
 * Social and general alerts use versioned IDs because Android does not let an app raise the
 * importance of an existing channel. Legacy channels are intentionally left in place. When a v2
 * channel is first created, observable user choices from its legacy channel are carried forward.
 */
object NotificationChannels {
    const val CHAT = "chat"
    const val SOCIAL_ALERTS = "social_alerts_v2"
    const val REMINDERS = "reminders"
    const val URGENT = "urgent"

    /** Fallback for unknown visible alerts and the FCM default declared in the manifest. */
    const val GENERAL_ALERTS = "general_alerts_v2"

    private const val LEGACY_SOCIAL = "social"
    private const val LEGACY_DEFAULT = "default"
    private const val VISIBILITY_NO_OVERRIDE = -1_000

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val frameworkManager = context.getSystemService(NotificationManager::class.java) ?: return
        val alertDefaults = alertDefaults()

        val channels = listOf(
            channel(
                context = context,
                id = CHAT,
                nameRes = R.string.channel_chat_name,
                descriptionRes = R.string.channel_chat_description,
                settings = alertDefaults,
            ),
            channel(
                context = context,
                id = URGENT,
                nameRes = R.string.channel_urgent_name,
                descriptionRes = R.string.channel_urgent_description,
                settings = alertDefaults,
            ),
            channel(
                context = context,
                id = SOCIAL_ALERTS,
                nameRes = R.string.channel_social_name,
                descriptionRes = R.string.channel_social_description,
                settings = versionedAlertSettings(
                    manager = frameworkManager,
                    currentId = SOCIAL_ALERTS,
                    legacyId = LEGACY_SOCIAL,
                    defaults = alertDefaults,
                ),
            ),
            channel(
                context = context,
                id = REMINDERS,
                nameRes = R.string.channel_reminders_name,
                descriptionRes = R.string.channel_reminders_description,
                settings = quietReminderDefaults(),
            ),
            channel(
                context = context,
                id = GENERAL_ALERTS,
                nameRes = R.string.channel_default_name,
                descriptionRes = R.string.channel_default_description,
                settings = versionedAlertSettings(
                    manager = frameworkManager,
                    currentId = GENERAL_ALERTS,
                    legacyId = LEGACY_DEFAULT,
                    defaults = alertDefaults,
                ),
            ),
        )

        // Recreating an existing channel is idempotent. Copying current v2 settings above also
        // avoids attempting to reset choices made after the migration.
        frameworkManager.createNotificationChannels(channels)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun channel(
        context: Context,
        id: String,
        nameRes: Int,
        descriptionRes: Int,
        settings: ChannelSettings,
    ): NotificationChannel =
        NotificationChannel(id, context.getString(nameRes), settings.importance).apply {
            description = context.getString(descriptionRes)
            setSound(settings.sound, settings.audioAttributes)
            vibrationPattern = settings.vibrationPattern
            enableVibration(settings.vibrate)
            lightColor = settings.lightColor
            enableLights(settings.lights)
            setShowBadge(settings.showBadge)
            lockscreenVisibility = settings.lockscreenVisibility
        }

    private fun alertDefaults(): ChannelSettings = ChannelSettings(
        importance = NotificationManager.IMPORTANCE_HIGH,
        sound = Settings.System.DEFAULT_NOTIFICATION_URI,
        audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build(),
        vibrate = true,
        vibrationPattern = null,
        lights = true,
        lightColor = Color.WHITE,
        showBadge = true,
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
    )

    private fun quietReminderDefaults(): ChannelSettings = ChannelSettings(
        importance = NotificationManager.IMPORTANCE_LOW,
        sound = null,
        audioAttributes = null,
        vibrate = false,
        vibrationPattern = null,
        lights = false,
        lightColor = 0,
        showBadge = false,
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
    )

    @RequiresApi(Build.VERSION_CODES.O)
    private fun versionedAlertSettings(
        manager: NotificationManager,
        currentId: String,
        legacyId: String,
        defaults: ChannelSettings,
    ): ChannelSettings {
        manager.getNotificationChannel(currentId)?.let { return ChannelSettings.from(it) }
        val legacy = manager.getNotificationChannel(legacyId) ?: return defaults
        val overrides = legacyAlertOverrides(
            LegacyAlertSignals(
                blocked = legacy.importance == NotificationManager.IMPORTANCE_NONE,
                userSetImportance = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    legacy.hasUserSetImportance(),
                importanceDiffers = legacy.importance != NotificationManager.IMPORTANCE_DEFAULT,
                userSetSound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    legacy.hasUserSetSound(),
                soundDiffers = legacy.sound != Settings.System.DEFAULT_NOTIFICATION_URI,
                vibrationDiffers = legacy.shouldVibrate() || legacy.vibrationPattern != null,
                lightsDiffer = legacy.shouldShowLights(),
                badgeDiffers = !legacy.canShowBadge(),
                visibilityDiffers = legacy.lockscreenVisibility != VISIBILITY_NO_OVERRIDE,
            ),
        )

        return defaults.copy(
            importance = if (overrides.importance) legacy.importance else defaults.importance,
            sound = if (overrides.sound) legacy.sound else defaults.sound,
            audioAttributes = if (overrides.sound) legacy.audioAttributes else defaults.audioAttributes,
            vibrate = if (overrides.vibration) legacy.shouldVibrate() else defaults.vibrate,
            vibrationPattern = if (overrides.vibration) {
                legacy.vibrationPattern?.clone()
            } else {
                defaults.vibrationPattern
            },
            lights = if (overrides.lights) legacy.shouldShowLights() else defaults.lights,
            lightColor = if (overrides.lights) legacy.lightColor else defaults.lightColor,
            showBadge = if (overrides.badge) legacy.canShowBadge() else defaults.showBadge,
            lockscreenVisibility = if (overrides.visibility) {
                legacy.lockscreenVisibility
            } else {
                defaults.lockscreenVisibility
            },
        )
    }

    private data class ChannelSettings(
        val importance: Int,
        val sound: Uri?,
        val audioAttributes: AudioAttributes?,
        val vibrate: Boolean,
        val vibrationPattern: LongArray?,
        val lights: Boolean,
        val lightColor: Int,
        val showBadge: Boolean,
        val lockscreenVisibility: Int,
    ) {
        companion object {
            @RequiresApi(Build.VERSION_CODES.O)
            fun from(channel: NotificationChannel): ChannelSettings = ChannelSettings(
                importance = channel.importance,
                sound = channel.sound,
                audioAttributes = channel.audioAttributes,
                vibrate = channel.shouldVibrate(),
                vibrationPattern = channel.vibrationPattern?.clone(),
                lights = channel.shouldShowLights(),
                lightColor = channel.lightColor,
                showBadge = channel.canShowBadge(),
                lockscreenVisibility = channel.lockscreenVisibility,
            )
        }
    }
}

/** Observable ways a legacy channel can differ from the app's original channel defaults. */
internal data class LegacyAlertSignals(
    val blocked: Boolean = false,
    val userSetImportance: Boolean = false,
    val importanceDiffers: Boolean = false,
    val userSetSound: Boolean = false,
    val soundDiffers: Boolean = false,
    val vibrationDiffers: Boolean = false,
    val lightsDiffer: Boolean = false,
    val badgeDiffers: Boolean = false,
    val visibilityDiffers: Boolean = false,
)

/** Settings that should be copied instead of taking the new high-alert defaults. */
internal data class LegacyAlertOverrides(
    val importance: Boolean,
    val sound: Boolean,
    val vibration: Boolean,
    val lights: Boolean,
    val badge: Boolean,
    val visibility: Boolean,
)

internal fun legacyAlertOverrides(signals: LegacyAlertSignals): LegacyAlertOverrides =
    LegacyAlertOverrides(
        importance = signals.blocked || signals.userSetImportance || signals.importanceDiffers,
        sound = signals.userSetSound || signals.soundDiffers,
        vibration = signals.vibrationDiffers,
        lights = signals.lightsDiffer,
        badge = signals.badgeDiffers,
        visibility = signals.visibilityDiffers,
    )
