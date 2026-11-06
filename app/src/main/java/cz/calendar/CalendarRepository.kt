package cz.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val multiDay: Boolean,
    val color: Int,
)

class CalendarRepository(private val resolver: ContentResolver) {

    fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR,
        )
        val cursor = resolver.query(
            Calendars.CONTENT_URI, projection, "${Calendars.SYNC_EVENTS} = 1", null, Calendars.ACCOUNT_NAME
        ) ?: return emptyList()

        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3)))
                }
            }
        }
    }

    fun eventsByDay(from: LocalDate, to: LocalDate, hidden: Set<Long>): Map<LocalDate, List<CalendarEvent>> {
        val zone = ZoneId.systemDefault()
        // All-day events are stored in UTC, so query a day wider on both sides
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.CALENDAR_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
        )

        val result = HashMap<LocalDate, MutableList<CalendarEvent>>()
        resolver.query(uri, projection, null, null, Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(1) in hidden) continue

                val allDay = c.getInt(5) == 1
                val eventZone = if (allDay) ZoneOffset.UTC else zone
                val start = toDate(c.getLong(3), eventZone)
                val last = toDate(maxOf(c.getLong(3), c.getLong(4) - 1), eventZone)
                val event = CalendarEvent(
                    eventId = c.getLong(0),
                    calendarId = c.getLong(1),
                    title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(bez názvu)",
                    begin = c.getLong(3),
                    end = c.getLong(4),
                    allDay = allDay,
                    multiDay = last > start,
                    color = c.getInt(6),
                )

                var day = maxOf(start, from)
                while (day <= last && day <= to) {
                    result.getOrPut(day) { mutableListOf() }.add(event)
                    day = day.plusDays(1)
                }
            }
        }

        return result.mapValues { (_, events) ->
            events.sortedWith(compareByDescending<CalendarEvent> { it.allDay || it.multiDay }.thenBy { it.begin })
        }
    }

    private fun toDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
