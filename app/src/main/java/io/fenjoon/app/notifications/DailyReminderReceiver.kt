package io.fenjoon.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.fenjoon.app.R

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FenjoonNotificationHelper.showSilentReminder(
            context = context,
            title = context.getString(R.string.daily_reminder_title)
        )
        DailyReminderScheduler.schedule(context)
    }
}
