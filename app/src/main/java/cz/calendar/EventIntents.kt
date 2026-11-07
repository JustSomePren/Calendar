package cz.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate

const val EXTRA_DAY = "day"
const val EXTRA_EVENT_ID = "event_id"
const val EXTRA_INSTANCE_BEGIN = "instance_begin"
const val EXTRA_END_DAY = "end_day"

fun openEventIntent(context: Context, event: CalendarEvent): Intent =
    Intent(context, EventEditActivity::class.java)
        .putExtra(EXTRA_EVENT_ID, event.eventId)
        .putExtra(EXTRA_INSTANCE_BEGIN, event.begin)

// With an end day the new event is all-day over the whole range, as picked by dragging in the month
fun newEventIntent(context: Context, day: LocalDate, endDay: LocalDate? = null): Intent =
    Intent(context, EventEditActivity::class.java)
        .putExtra(EXTRA_DAY, day.toString())
        .apply { endDay?.let { putExtra(EXTRA_END_DAY, it.toString()) } }

// Opens the detail of a day, from the month, the widget or a reminder
fun dayIntent(context: Context, day: LocalDate): Intent =
    Intent(context, DayActivity::class.java)
        .setData(Uri.parse("calendar-day:$day"))
        .putExtra(EXTRA_DAY, day.toString())

fun Intent.dayExtra(): LocalDate? = getStringExtra(EXTRA_DAY)?.let(LocalDate::parse)
