package cz.calendar

import android.content.Context
import android.provider.CalendarContract
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration

private const val HOURLY = "refresh-hourly"
private const val WATCH = "refresh-on-change"

// Refreshes the widget and reminders whenever calendar data changes
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        EventNotifier.run(applicationContext)
        MonthWidget().updateAll(applicationContext)
        // A content trigger fires only once, so wait for the next change again
        if (WATCH in tags) BackgroundWork.watch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

object BackgroundWork {

    fun start(context: Context) {
        // Hourly refresh also moves the "today" mark after midnight
        val hourly = PeriodicWorkRequestBuilder<RefreshWorker>(Duration.ofHours(1)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(HOURLY, ExistingPeriodicWorkPolicy.KEEP, hourly)
        watch(context, ExistingWorkPolicy.KEEP)
    }

    fun watch(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
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
