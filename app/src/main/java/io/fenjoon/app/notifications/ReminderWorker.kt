package io.fenjoon.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.fenjoon.app.R

/**
 * Posts the silent inactivity reminder (LOW-importance `reminders` channel, one line, no image).
 * Runs on WorkManager's background executor. If notifications aren't permitted we just succeed
 * quietly rather than retry.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!notificationsPermitted(context)) return Result.success()

        Notifier.show(
            context,
            NotificationPayload(
                type = NotificationPayload.Type.REMINDER,
                title = context.getString(R.string.reminder_title),
                body = context.getString(R.string.reminder_body),
                url = null,
                tag = REMINDER_TAG,
                imageUrl = null,
                sender = null,
                conversationId = null,
                messageId = null,
                senderId = null,
                sentAt = null,
                sentAtMillis = null,
                replyUrl = null,
                readUrl = null,
                unreadCount = 0,
                conversationType = null,
                conversationTitle = null,
                senderImageUrl = null,
                conversationImageUrl = null,
                action = null,
                targetType = null,
                targetId = null,
                chapterId = null,
                blockId = null,
                notificationId = REMINDER_ID,
            ),
        )
        return Result.success()
    }

    private fun notificationsPermitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REMINDER_TAG = "reminder"
        const val REMINDER_ID = 424242
    }
}
