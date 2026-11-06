package cz.calendar

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import java.time.LocalDate
import java.time.ZoneId

fun openEventIntent(event: CalendarEvent): Intent =
    Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, event.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)

fun newEventIntent(day: LocalDate): Intent {
    val begin = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return Intent(Intent.ACTION_INSERT, Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000)
}
