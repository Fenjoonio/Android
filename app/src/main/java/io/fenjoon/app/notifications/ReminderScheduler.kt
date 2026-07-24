package io.fenjoon.app.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the local "come back" reminder. [rearm] is called on every app open and REPLACEs the
 * pending work, so the reminder only ever fires after [DELAY_DAYS] of no launches — i.e. it's an
 * inactivity nudge, not a fixed daily ping. Inexact by design (no exact-alarm permission needed).
 */
object ReminderScheduler {
    private const val WORK_NAME = "fenjoon_inactivity_reminder"
    private const val DELAY_DAYS = 3L

    fun rearm(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(DELAY_DAYS, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
