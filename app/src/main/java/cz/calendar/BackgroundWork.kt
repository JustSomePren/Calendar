package cz.calendar

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration

private const val WATCH = "refresh-on-change"

// Refreshes the widget and reminders whenever calendar data changes
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repairIds(applicationContext)
        EventNotifier.run(applicationContext)
        refreshMonthWidgets(applicationContext)
        // A synced change can move a vacation, so the gallery reads them again
        if (Settings(applicationContext).hasVacations) GalleryLink.notifyChanged(applicationContext)
        // A content trigger fires only once, so wait for the next change again
        if (WATCH in tags) BackgroundWork.watch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

object BackgroundWork {

    // No periodic work: reminders and midnight use alarms, data changes use a content trigger
    fun start(context: Context, refreshWidget: Boolean = false) {
        // Watching the calendar needs access to it
        if (!context.hasCalendarAccess()) return
        // Earlier versions refreshed every hour
        WorkManager.getInstance(context).cancelUniqueWork("refresh-hourly")
        watch(context, ExistingWorkPolicy.KEEP)
        if (refreshWidget) refreshNow(context)
    }

    fun watch(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            // Sync often writes in bursts, so handle them together
            .setTriggerContentUpdateDelay(Duration.ofSeconds(30))
            .setTriggerContentMaxDelay(Duration.ofMinutes(2))
            .build()
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints)
            .addTag(WATCH)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH, policy, request)
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
    }
}
