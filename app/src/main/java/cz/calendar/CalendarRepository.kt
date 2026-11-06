package cz.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.core.content.contentValuesOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val writable: Boolean,
    val primary: Boolean,
)

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
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
        )
        val cursor = resolver.query(
            Calendars.CONTENT_URI, projection, "${Calendars.SYNC_EVENTS} = 1", null, Calendars.ACCOUNT_NAME
        ) ?: return emptyList()

        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1).orEmpty(),
                            account = c.getString(2).orEmpty(),
                            color = c.getInt(3),
                            writable = c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                            primary = c.getInt(5) == 1,
                        )
                    )
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
        val notCanceled = "${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED}"

        val result = HashMap<LocalDate, MutableList<CalendarEvent>>()
        resolver.query(uri, projection, notCanceled, null, Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(1) in hidden) continue

                val allDay = c.getInt(5) == 1
                val days = eventDays(c.getLong(3), c.getLong(4), if (allDay) ZoneOffset.UTC else zone)
                val event = CalendarEvent(
                    eventId = c.getLong(0),
                    calendarId = c.getLong(1),
                    title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(bez názvu)",
                    begin = c.getLong(3),
                    end = c.getLong(4),
                    allDay = allDay,
                    multiDay = days.endInclusive > days.start,
                    color = c.getInt(6),
                )

                var day = maxOf(days.start, from)
                while (day <= days.endInclusive && day <= to) {
                    result.getOrPut(day) { mutableListOf() }.add(event)
                    day = day.plusDays(1)
                }
            }
        }

        return result.mapValues { (_, events) ->
            events.sortedWith(compareByDescending<CalendarEvent> { it.allDay || it.multiDay }.thenBy { it.begin })
        }
    }

    fun event(id: Long): EventDraft? {
        val projection = arrayOf(
            Events.CALENDAR_ID,
            Events.TITLE,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.DTSTART,
            Events.DTEND,
            Events.DURATION,
            Events.ALL_DAY,
            Events.RRULE,
        )
        val draft = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null

            val allDay = c.getInt(7) == 1
            val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
            val begin = c.getLong(4)
            val end = if (c.isNull(5)) begin + parseDuration(c.getString(6)).toMillis() else c.getLong(5)
            val start = Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()
            val finish = if (allDay) {
                eventDays(begin, end, zone).endInclusive.atStartOfDay()
            } else {
                Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime()
            }

            EventDraft(
                id = id,
                calendarId = c.getLong(0),
                title = c.getString(1).orEmpty(),
                location = c.getString(2).orEmpty(),
                description = c.getString(3).orEmpty(),
                allDay = allDay,
                start = start,
                end = finish,
                rule = c.getString(8)?.takeIf { it.isNotBlank() },
            )
        } ?: return null

        return draft.copy(reminder = firstReminder(id))
    }

    fun saveEvent(draft: EventDraft, updateReminder: Boolean) {
        val values = eventValues(draft)
        val id = if (draft.id == null) {
            values.put(Events.CALENDAR_ID, draft.calendarId)
            val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("Insert failed")
            ContentUris.parseId(uri)
        } else {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, draft.id), values, null, null)
            draft.id
        }

        if (updateReminder) {
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
            draft.reminder?.let { minutes ->
                val reminder = contentValuesOf(
                    Reminders.EVENT_ID to id,
                    Reminders.MINUTES to minutes,
                    Reminders.METHOD to Reminders.METHOD_ALERT,
                )
                resolver.insert(Reminders.CONTENT_URI, reminder)
            }
        }
    }

    fun deleteEvent(id: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
    }

    // Cancels a single occurrence of a recurring event
    fun deleteOccurrence(id: Long, instanceBegin: Long) {
        val values = contentValuesOf(
            Events.ORIGINAL_INSTANCE_TIME to instanceBegin,
            Events.STATUS to Events.STATUS_CANCELED,
        )
        resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), values)
    }

    private fun eventValues(draft: EventDraft) = ContentValues().apply {
        put(Events.TITLE, draft.title.trim())
        put(Events.EVENT_LOCATION, draft.location.trim())
        put(Events.DESCRIPTION, draft.description.trim())
        put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
        put(Events.DTSTART, draft.startMillis())
        put(Events.EVENT_TIMEZONE, draft.timeZone)
        if (draft.rule != null) {
            put(Events.RRULE, draft.rule)
            put(Events.DURATION, draft.durationRule())
            putNull(Events.DTEND)
        } else {
            put(Events.DTEND, draft.endMillis())
            putNull(Events.RRULE)
            putNull(Events.DURATION)
        }
    }

    private fun firstReminder(eventId: Long): Int? =
        resolver.query(
            Reminders.CONTENT_URI,
            arrayOf(Reminders.MINUTES),
            "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            Reminders.MINUTES,
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
}
