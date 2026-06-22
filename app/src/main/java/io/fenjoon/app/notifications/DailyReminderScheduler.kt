package io.fenjoon.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object DailyReminderScheduler {
    private const val REQUEST_CODE = 1610

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextReminderTimeMillis(),
            AlarmManager.INTERVAL_DAY,
            createPendingIntent(context)
        )
    }

    private fun nextReminderTimeMillis(): Long {
        val reminderTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 16)
            set(Calendar.MINUTE, 10)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return reminderTime.timeInMillis
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
