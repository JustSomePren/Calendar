package cz.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.contentValuesOf
import java.time.Duration
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
    val important: Boolean = false,
)

data class Attendee(val email: String, val status: Int) {
    val declined: Boolean
        get() = status == Attendees.ATTENDEE_STATUS_DECLINED

    val statusLabel: String
        get() = when (status) {
            Attendees.ATTENDEE_STATUS_ACCEPTED -> "Přijato"
            Attendees.ATTENDEE_STATUS_DECLINED -> "Odmítnuto"
            Attendees.ATTENDEE_STATUS_TENTATIVE -> "Možná"
            else -> "Bez odpovědi"
        }
}

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
        return query(Calendars.CONTENT_URI, projection, "${Calendars.SYNC_EVENTS} = 1", sortOrder = Calendars.ACCOUNT_NAME) { c ->
            CalendarInfo(
                id = c.getLong(0),
                name = c.getString(1).orEmpty(),
                account = c.getString(2).orEmpty(),
                color = c.getInt(3),
                writable = c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                primary = c.getInt(5) == 1,
            )
        }
    }

    fun eventsByDay(
        from: LocalDate,
        to: LocalDate,
        hidden: Set<Long>,
        important: Set<Long> = emptySet(),
    ): Map<LocalDate, List<CalendarEvent>> {
        val zone = ZoneId.systemDefault()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.CALENDAR_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
            Instances.ORIGINAL_ID,
        )

        val result = HashMap<LocalDate, MutableList<CalendarEvent>>()
        instances(from, to, projection) { c ->
            if (c.getLong(1) in hidden) return@instances

            val allDay = c.getInt(5) == 1
            val days = eventDays(c.getLong(3), c.getLong(4), if (allDay) ZoneOffset.UTC else zone)
            val event = CalendarEvent(
                eventId = c.getLong(0),
                calendarId = c.getLong(1),
                title = titleOrDefault(c.getString(2)),
                begin = c.getLong(3),
                end = c.getLong(4),
                allDay = allDay,
                multiDay = days.endInclusive > days.start,
                color = c.getInt(6),
                important = c.getLong(0) in important || (!c.isNull(7) && c.getLong(7) in important),
            )

            var day = maxOf(days.start, from)
            while (day <= days.endInclusive && day <= to) {
                result.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
            }
        }

        return result.mapValues { (_, events) ->
            events.sortedWith(compareByDescending<CalendarEvent> { it.allDay || it.multiDay }.thenBy { it.begin })
        }
    }

    fun upcoming(from: LocalDate, to: LocalDate): List<UpcomingEvent> {
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.CALENDAR_ID,
            Instances.TITLE,
            Instances.EVENT_LOCATION,
            Instances.DESCRIPTION,
            Instances.ORGANIZER,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.RRULE,
            Instances.ORIGINAL_ID,
        )
        val accounts = calendars().associate { it.id to it.account }
        val result = mutableListOf<UpcomingEvent>()
        instances(from, to, projection) { c ->
            result += UpcomingEvent(
                eventId = c.getLong(0),
                calendarId = c.getLong(1),
                title = titleOrDefault(c.getString(2)),
                location = c.getString(3).orEmpty(),
                description = c.getString(4).orEmpty(),
                organizer = c.getString(5).orEmpty(),
                begin = c.getLong(6),
                end = c.getLong(7),
                allDay = c.getInt(8) == 1,
                // A changed occurrence has no rule of its own but still belongs to a series
                recurring = !c.getString(9).isNullOrBlank() || !c.isNull(10),
                account = accounts[c.getLong(1)].orEmpty(),
                seriesId = if (c.isNull(10)) c.getLong(0) else c.getLong(10),
            )
        }
        return result
    }

    fun attendees(eventId: Long): List<Attendee> =
        query(
            Attendees.CONTENT_URI,
            arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
        ) { c -> Attendee(c.getString(0).orEmpty(), c.getInt(1)) }
            .filter { it.email.isNotBlank() }

    fun event(id: Long, instanceBegin: Long? = null): EventDraft? {
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
            Events.ORGANIZER,
            Events.ORIGINAL_ID,
            Events.EVENT_TIMEZONE,
        )
        val draft = query(ContentUris.withAppendedId(Events.CONTENT_URI, id), projection) { c ->
            val allDay = c.getInt(7) == 1
            val rule = c.getString(8)?.takeIf { it.isNotBlank() }
            val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
            val storedZone = c.getString(11)?.takeIf { !allDay && it.isNotBlank() }
            // Series dates are counted in the zone the series is kept in
            val seriesZone = if (allDay) ZoneOffset.UTC else storedZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            val seriesStart = c.getLong(4)
            val length = if (c.isNull(5)) parseDuration(c.getString(6)).toMillis() else c.getLong(5) - seriesStart

            // A recurring event opened from an occurrence shows that occurrence
            val occurrence = instanceBegin?.takeIf { rule != null }
            val begin = occurrence ?: seriesStart
            val end = begin + length
            val start = Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()
            val finish = if (allDay) {
                eventDays(begin, end, zone).endInclusive.atStartOfDay()
            } else {
                Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime()
            }
            val organizer = c.getString(9).orEmpty()

            EventDraft(
                id = id,
                calendarId = c.getLong(0),
                title = c.getString(1).orEmpty(),
                location = c.getString(2).orEmpty(),
                description = c.getString(3).orEmpty(),
                allDay = allDay,
                start = start,
                end = finish,
                rule = rule,
                instanceBegin = occurrence,
                seriesStartDate = occurrence?.let { Instant.ofEpochMilli(seriesStart).atZone(seriesZone).toLocalDate() },
                instanceDate = occurrence?.let { Instant.ofEpochMilli(it).atZone(seriesZone).toLocalDate() },
                loadedBegin = begin.takeIf { !allDay },
                loadedEnd = end.takeIf { !allDay },
                loadedSeriesStart = occurrence?.let { seriesStart },
                eventTimeZone = storedZone,
                organizer = organizer,
                originalId = if (c.isNull(10)) null else c.getLong(10),
            ) to organizer
        }.firstOrNull() ?: return null

        val (event, organizer) = draft
        val guests = attendees(id).map { it.email }.filterNot { it.equals(organizer, ignoreCase = true) }
        return event.copy(attendees = guests)
    }

    // Returns the id of the saved event
    fun saveEvent(draft: EventDraft): Long {
        // A series moved to other days needs its weekdays moved too
        val rule = draft.rule?.let { shiftRule(it, draft.seriesDayShift()) ?: error("Rule cannot move") }
        val values = eventValues(draft.copy(rule = rule))
        val newStart = draft.seriesStartMillis()
        values.put(Events.DTSTART, newStart)
        if (draft.id != null) {
            val seriesZone = if (draft.allDay) ZoneOffset.UTC else ZoneId.of(draft.timeZone)
            if (rule != null) moveSeriesHistory(draft.id, newStart, seriesZone, draft.allDay) else dropSeriesHistory(draft.id)
        }
        val id = if (draft.id == null) {
            values.put(Events.CALENDAR_ID, draft.calendarId)
            ContentUris.parseId(resolver.insert(Events.CONTENT_URI, values) ?: error("Insert failed"))
        } else {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, draft.id), values, null, null)
            draft.id
        }
        saveAttendees(id, draft.attendees)
        return id
    }

    // A series that stops repeating keeps no changed or skipped occurrences of its own
    private fun dropSeriesHistory(seriesId: Long) {
        resolver.delete(Events.CONTENT_URI, "${Events.ORIGINAL_ID} = ?", arrayOf(seriesId.toString()))
    }

    // Skipped and changed occurrences point at the old times of a series, move them along with it.
    // The series may also switch zone (all-day ones live in UTC), so read old times in the old zone.
    private fun moveSeriesHistory(seriesId: Long, newStart: Long, newZone: ZoneId, newAllDay: Boolean) {
        val (oldStart, zone, excluded) = query(
            ContentUris.withAppendedId(Events.CONTENT_URI, seriesId),
            arrayOf(Events.DTSTART, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EXDATE),
        ) { c ->
            val zone = if (c.getInt(1) == 1) ZoneOffset.UTC else c.getString(2)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            Triple(c.getLong(0), zone, c.getString(3))
        }.firstOrNull() ?: return
        if (oldStart == newStart) return

        // By the clock, so a move keeps the same local time across daylight-saving changes
        val shift = Duration.between(Instant.ofEpochMilli(oldStart).atZone(zone).toLocalDateTime(), Instant.ofEpochMilli(newStart).atZone(newZone).toLocalDateTime())
        fun moved(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().plus(shift).atZone(newZone).toInstant().toEpochMilli()

        query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.ORIGINAL_INSTANCE_TIME),
            "${Events.ORIGINAL_ID} = ?",
            arrayOf(seriesId.toString()),
        ) { it.getLong(0) to it.getLong(1) }.forEach { (exceptionId, instance) ->
            resolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, exceptionId),
                contentValuesOf(Events.ORIGINAL_INSTANCE_TIME to moved(instance)),
                null,
                null,
            )
        }

        // EXDATE as written by this app or Google: a list of UTC times or dates
        if (excluded != null && ';' !in excluded) {
            val utc = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
            val date = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
            val shifted = excluded.split(',').map { it.trim() }.joinToString(",") { value ->
                runCatching {
                    val old = if (value.length == 8) LocalDate.parse(value, date).atStartOfDay().toInstant(ZoneOffset.UTC) else Instant.from(utc.parse(value))
                    // Written the way the series now repeats, dates only for all-day ones
                    (if (newAllDay) date else utc).format(Instant.ofEpochMilli(moved(old.toEpochMilli())))
                }.getOrDefault(value)
            }
            resolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, seriesId),
                contentValuesOf(Events.EXDATE to shifted),
                null,
                null,
            )
        }
    }

    // Changes just one occurrence of a recurring event, returns the id of the exception
    fun saveOccurrence(draft: EventDraft): Long {
        val id = requireNotNull(draft.id)
        val values = eventValues(draft.copy(rule = null))
        values.put(Events.ORIGINAL_INSTANCE_TIME, requireNotNull(draft.instanceBegin))
        values.remove(Events.RRULE)
        values.remove(Events.DURATION)
        val uri = resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), values)
            ?: error("Insert failed")
        return ContentUris.parseId(uri)
    }

    fun deleteEvent(id: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
    }

    // Cancels an occurrence that was already changed on its own
    fun cancelOccurrence(exceptionId: Long) {
        resolver.update(
            ContentUris.withAppendedId(Events.CONTENT_URI, exceptionId),
            contentValuesOf(Events.STATUS to Events.STATUS_CANCELED),
            null,
            null,
        )
    }

    // Cancels a single occurrence of a recurring event
    fun deleteOccurrence(id: Long, instanceBegin: Long) {
        val values = contentValuesOf(
            Events.ORIGINAL_INSTANCE_TIME to instanceBegin,
            Events.STATUS to Events.STATUS_CANCELED,
        )
        resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), values)
    }

    fun findException(seriesId: Long, instanceBegin: Long): Long? =
        query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} = 0",
            arrayOf(seriesId.toString(), instanceBegin.toString()),
        ) { it.getLong(0) }.firstOrNull()

    fun findEvent(calendarId: Long, title: String, begin: Long): Long? =
        query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.TITLE} = ? AND ${Events.DTSTART} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString(), title.trim(), begin.toString()),
        ) { it.getLong(0) }.firstOrNull()

    private fun saveAttendees(eventId: Long, emails: List<String>) {
        val current = attendees(eventId).map { it.email.lowercase() }
        val wanted = emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        (current - wanted.toSet()).forEach { email ->
            resolver.delete(
                Attendees.CONTENT_URI,
                "${Attendees.EVENT_ID} = ? AND LOWER(${Attendees.ATTENDEE_EMAIL}) = ? AND ${Attendees.ATTENDEE_RELATIONSHIP} != ?",
                arrayOf(eventId.toString(), email, Attendees.RELATIONSHIP_ORGANIZER.toString()),
            )
        }
        (wanted - current.toSet()).forEach { email ->
            val values = contentValuesOf(
                Attendees.EVENT_ID to eventId,
                Attendees.ATTENDEE_EMAIL to email,
                Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE,
                Attendees.ATTENDEE_TYPE to Attendees.TYPE_REQUIRED,
                Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
            )
            resolver.insert(Attendees.CONTENT_URI, values)
        }
    }

    private fun eventValues(draft: EventDraft) = ContentValues().apply {
        put(Events.TITLE, draft.title.trim())
        put(Events.EVENT_LOCATION, draft.location.trim())
        put(Events.DESCRIPTION, draft.description.trim())
        put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
        put(Events.DTSTART, draft.startMillis())
        put(Events.EVENT_TIMEZONE, draft.timeZone)
        put(Events.HAS_ATTENDEE_DATA, if (draft.attendees.isEmpty()) 0 else 1)
        if (draft.rule != null) {
            put(Events.RRULE, draft.rule)
            put(Events.DURATION, draft.durationRule())
            putNull(Events.DTEND)
            draft.excludedDates?.let { put(Events.EXDATE, it) }
        } else {
            put(Events.DTEND, draft.endMillis())
            putNull(Events.RRULE)
            putNull(Events.DURATION)
        }
    }

    private fun instances(from: LocalDate, to: LocalDate, projection: Array<String>, onRow: (Cursor) -> Unit) {
        val zone = ZoneId.systemDefault()
        // All-day events are stored in UTC, so query a day wider on both sides
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val notCanceled = "${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED}"
        resolver.query(uri, projection, notCanceled, null, Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) onRow(c)
        }
    }

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        sortOrder: String? = null,
        read: (Cursor) -> T,
    ): List<T> =
        resolver.query(uri, projection, selection, args, sortOrder)?.use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }.orEmpty()

    private fun titleOrDefault(title: String?) = title?.takeIf { it.isNotBlank() } ?: "(bez názvu)"
}
