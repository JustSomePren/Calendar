package cz.calendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.concurrent.thread

private const val CHANNEL_LOW = "reminders_low"
private const val CHANNEL_NORMAL = "reminders"
private const val CHANNEL_HIGH = "reminders_high"

// Shows reminders that are due and sets an alarm for the next one
object EventNotifier {

    private data class Planned(val key: String, val at: Long, val event: UpcomingEvent, val priority: Priority)

    @Synchronized
    fun run(context: Context, now: Long = System.currentTimeMillis()) {
        if (!context.hasCalendarAccess()) return

        val repository = CalendarRepository(context.contentResolver)
        val settings = Settings(context)
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val emails = settings.workEmails
        val keywords = settings.workKeywords

        // Multi-day events remind 3 days ahead, so look a bit further
        val planned = repository.upcoming(today.minusDays(1), today.plusDays(5))
            .filter { event ->
                shouldNotify(settings.category(event.calendarId), settings.watchFor(event.eventId, event.seriesId), event.isMine) {
                    matchesWorkFilter(event, repository.attendees(event.eventId), emails, keywords)
                }
            }
            .flatMap { event ->
                val priority = settings.priorityFor(event.eventId, event.seriesId)
                reminderTimes(event, priority, zone).map { at ->
                    Planned("${event.eventId}:${event.begin}:$at", at, event, priority)
                }
            }

        val fired = settings.firedReminders
        val due = planned.filter { it.at <= now && it.key !in fired && now < maxOf(it.event.begin, it.event.end) }
        createChannels(context)
        // On the very first run old reminders are only marked, not shown
        if (settings.notifierStarted) due.forEach { show(context, it.event, it.priority, now, zone) }
        settings.notifierStarted = true

        // Forget reminders older than a week so the list stays small
        val weekAgo = now - 7 * 24 * 3600_000L
        settings.firedReminders = (fired + due.map { it.key })
            .filter { it.substringAfterLast(':').toLongOrNull()?.let { at -> at > weekAgo } == true }
            .toSet()

        scheduleNext(context, planned.filter { it.at > now }.minOfOrNull { it.at })
        scheduleMidnight(context, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    private fun show(context: Context, event: UpcomingEvent, priority: Priority, now: Long, zone: ZoneId) {
        if (!context.canNotify()) return

        val day = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else zone).start
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_DAY, day.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = "${event.eventId}:${event.begin}".hashCode()
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = listOf(whenText(event, now, zone), event.location).filter { it.isNotBlank() }.joinToString(" · ")

        val notification = NotificationCompat.Builder(context, channelFor(priority))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .addAction(0, "Neupozorňovat", muteIntent(context, event.eventId, id))
            .setWhen(event.begin)
            .setShowWhen(false)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    // Turns reminders off for the event, for a recurring one the whole series
    private fun muteIntent(context: Context, eventId: Long, notificationId: Int) = PendingIntent.getBroadcast(
        context,
        notificationId,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_MUTE)
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channelFor(priority: Priority) = when (priority) {
        Priority.HIGH -> CHANNEL_HIGH
        Priority.LOW -> CHANNEL_LOW
        else -> CHANNEL_NORMAL
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LOW, "Upozornění – nízká priorita", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_NORMAL, "Upozornění", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_HIGH, "Upozornění – vysoká priorita", NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    // Only reminders wake the phone up
    private fun scheduleNext(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = receiverIntent(context, 0, null)
        when {
            at == null -> alarms.cancel(intent)
            alarms.canScheduleExactAlarms() -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    // Moves the widget's "today" after midnight, waits until the phone wakes up anyway
    private fun scheduleMidnight(context: Context, at: Long) {
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, receiverIntent(context, 1, ACTION_NEW_DAY))
    }

    private fun receiverIntent(context: Context, code: Int, action: String?) = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, ReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private const val ACTION_NEW_DAY = "cz.calendar.NEW_DAY"
const val ACTION_MUTE = "cz.calendar.MUTE"
const val EXTRA_NOTIFICATION_ID = "notification_id"

// Fires for alarms, after a restart or app update and when the clock changes
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MUTE) {
            Settings(context).setWatch(intent.getLongExtra(EXTRA_EVENT_ID, -1), Watch.NEVER)
            context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
            return
        }
        // Anything but a reminder may also change what the widget shows
        if (intent.action != null) BackgroundWork.start(context, refreshWidget = true)
        val pending = goAsync()
        thread {
            try {
                EventNotifier.run(context)
            } finally {
                pending.finish()
            }
        }
    }
}
