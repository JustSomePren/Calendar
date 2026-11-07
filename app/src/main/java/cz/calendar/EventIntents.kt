package cz.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate

const val EXTRA_DAY = "day"
const val EXTRA_EVENT_ID = "event_id"
const val EXTRA_INSTANCE_BEGIN = "instance_begin"

fun openEventIntent(context: Context, event: CalendarEvent): Intent =
    Intent(context, EventEditActivity::class.java)
        .putExtra(EXTRA_EVENT_ID, event.eventId)
        .putExtra(EXTRA_INSTANCE_BEGIN, event.begin)

fun newEventIntent(context: Context, day: LocalDate): Intent =
    Intent(context, EventEditActivity::class.java).putExtra(EXTRA_DAY, day.toString())

// Opens a day in the month screen. The data makes it differ from the app icon's intent, so it opens on top
// of an unsaved editor (Back returns to it) instead of only bringing the app forward.
fun dayIntent(context: Context, day: LocalDate): Intent =
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse("calendar-day:$day"))
        .putExtra(EXTRA_DAY, day.toString())

fun Intent.dayExtra(): LocalDate? = getStringExtra(EXTRA_DAY)?.let(LocalDate::parse)
