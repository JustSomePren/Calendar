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
    // Off for calendars of an account that the phone does not sync yet
    val synced: Boolean = true,
    val accountType: String = "",
) {
    val local: Boolean
        get() = accountType == android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL
}

// What orders events within a day, see eventRank
class Ranking(
    val category: (Long) -> Category,
    val isCreated: (Long) -> Boolean,
    val emails: List<String>,
    val keywords: List<String>,
    val watched: (eventId: Long, seriesId: Long) -> Boolean = { _, _ -> false },
    val onlyMine: Boolean = false,
)

fun Settings.ranking() = Ranking(
    ::category, ::isCreated, workEmails, workKeywords,
    watched = { id, series -> watchFor(id, series) == Watch.ALWAYS },
    onlyMine = onlyMine,
)

// Rank of work events that do not concern me, see eventRank
private const val OTHER_WORK = 2

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
    val rank: Int = 0,
    val organizer: String = "",
    val location: String = "",
    val description: String = "",
    val seriesId: Long = eventId,
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

const val LOCAL_ACCOUNT = "Tento telefon"

class CalendarRepository(private val resolver: ContentResolver) {

    fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.SYNC_EVENTS,
            Calendars.ACCOUNT_TYPE,
        )
        // Also calendars the phone does not sync yet, e.g. a work calendar added in Google Calendar on the web
        return query(Calendars.CONTENT_URI, projection, sortOrder = Calendars.ACCOUNT_NAME) { c ->
            CalendarInfo(
                id = c.getLong(0),
                name = c.getString(1).orEmpty(),
                account = c.getString(2).orEmpty(),
                color = c.getInt(3),
                writable = c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                primary = c.getInt(5) == 1,
                synced = c.getInt(6) == 1,
                accountType = c.getString(7).orEmpty(),
            )
        }
    }

    // Apps may switch a calendar's sync on; then ask the account to sync so its events come in
    fun startSyncing(calendar: CalendarInfo) {
        resolver.update(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id),
            contentValuesOf(Calendars.SYNC_EVENTS to 1, Calendars.VISIBLE to 1),
            null,
            null,
        )
        if (!calendar.local) requestCalendarSync(android.accounts.Account(calendar.account, calendar.accountType))
    }

    // An account's calendar stays in the account; it only stops coming to this phone
    fun stopSyncing(calendar: CalendarInfo) {
        resolver.update(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id),
            contentValuesOf(Calendars.SYNC_EVENTS to 0, Calendars.VISIBLE to 0),
            null,
            null,
        )
    }

    // A calendar made on this phone goes away with its events
    fun deleteLocalCalendar(calendar: CalendarInfo) {
        resolver.delete(ContentUris.withAppendedId(localOwner(), calendar.id), null, null)
    }

    // Local calendars may be created and deleted only by their "owner", which here is this app
    private fun localOwner() = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    // Sends local changes to the server and fetches new ones, for every account with calendars
    fun syncAll(calendars: List<CalendarInfo>) {
        calendars.filter { it.synced && !it.local }
            .map { android.accounts.Account(it.account, it.accountType) }
            .distinct()
            .forEach(::requestCalendarSync)
    }

    // A calendar kept only on this phone, for use without any account
    fun createLocalCalendar(name: String, color: Int): Long {
        val asOwner = localOwner()
        val values = contentValuesOf(
            Calendars.ACCOUNT_NAME to LOCAL_ACCOUNT,
            Calendars.ACCOUNT_TYPE to android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL,
            Calendars.NAME to name,
            Calendars.CALENDAR_DISPLAY_NAME to name,
            Calendars.CALENDAR_COLOR to color,
            Calendars.CALENDAR_ACCESS_LEVEL to Calendars.CAL_ACCESS_OWNER,
            Calendars.OWNER_ACCOUNT to LOCAL_ACCOUNT,
            Calendars.CALENDAR_TIME_ZONE to ZoneId.systemDefault().id,
            Calendars.VISIBLE to 1,
            Calendars.SYNC_EVENTS to 1,
        )
        return ContentUris.parseId(resolver.insert(asOwner, values) ?: error("Insert failed"))
    }

    fun eventsByDay(
        from: LocalDate,
        to: LocalDate,
        hidden: Set<Long>,
        important: Set<Long> = emptySet(),
        ranking: Ranking? = null,
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
            Instances.ORGANIZER,
            Instances.EVENT_LOCATION,
            Instances.DESCRIPTION,
        )

        val result = HashMap<LocalDate, MutableList<CalendarEvent>>()
        val all = mutableListOf<CalendarEvent>()
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
                organizer = c.getString(8).orEmpty(),
                location = c.getString(9).orEmpty(),
                description = c.getString(10).orEmpty(),
                seriesId = if (c.isNull(7)) c.getLong(0) else c.getLong(7),
            )
            all += event

            var day = maxOf(days.start, from)
            while (day <= days.endInclusive && day <= to) {
                result.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
            }
        }

        val ranks = ranking?.let { rank(all, it) }.orEmpty()
        val onlyMine = ranking?.onlyMine == true
        return result.mapValues { (_, events) ->
            events.map { it.copy(rank = ranks[it.eventId to it.begin] ?: 0) }
                .filter { !onlyMine || it.rank != OTHER_WORK }
                .sortedWith(compareBy<CalendarEvent> { it.rank }.thenByDescending { it.allDay || it.multiDay }.thenBy { it.begin })
        }
    }

    private fun rank(events: List<CalendarEvent>, ranking: Ranking): Map<Pair<Long, Long>, Int> {
        val accounts = calendars().associate { it.id to it.account }
        val work = events.filter { ranking.category(it.calendarId) == Category.WORK }.map { it.eventId }.distinct()
        val attendees = if (ranking.emails.isEmpty() || work.isEmpty()) emptyMap() else attendeesOf(work)
        return events.associate { event ->
            val upcoming = UpcomingEvent(
                eventId = event.eventId, calendarId = event.calendarId, title = event.title, location = event.location,
                description = event.description, organizer = event.organizer, account = accounts[event.calendarId].orEmpty(),
                begin = event.begin, end = event.end, allDay = event.allDay,
            )
            val mine = upcoming.isMine || ranking.isCreated(event.eventId) || ranking.watched(event.eventId, event.seriesId)
            (event.eventId to event.begin) to eventRank(ranking.category(event.calendarId), mine) {
                matchesWorkFilter(upcoming, attendees[event.eventId].orEmpty(), ranking.emails, ranking.keywords)
            }
        }
    }

    // Attendees of many events in one query
    private fun attendeesOf(eventIds: List<Long>): Map<Long, List<Attendee>> =
        eventIds.chunked(500).flatMap { chunk ->
            query(
                Attendees.CONTENT_URI,
                arrayOf(Attendees.EVENT_ID, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS),
                "${Attendees.EVENT_ID} IN (${chunk.joinToString(",") { "?" }})",
                chunk.map { it.toString() }.toTypedArray(),
            ) { c -> c.getLong(0) to Attendee(c.getString(1).orEmpty(), c.getInt(2)) }
        }.groupBy({ it.first }, { it.second })

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

    // Account and Google calendar id, the same on every phone, unlike the id
    fun calendarKeys(): Map<Long, String> =
        query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME),
        ) { c ->
            c.getLong(0) to listOf(c.getString(1), c.getString(2), c.getString(3) ?: c.getString(4)).joinToString("|") { it.orEmpty() }
        }.toMap()

    // Google event id once synced, otherwise title and start. The better key comes first.
    fun eventKeys(calendars: Map<Long, String>, ids: Set<Long>? = null): Map<Long, List<String>> {
        val selection = ids?.let { "${Events._ID} IN (${it.joinToString()})" }
        val projection = arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.TITLE, Events.DTSTART, Events.DELETED)
        return query(Events.CONTENT_URI, projection, selection) { c ->
            val calendar = calendars[c.getLong(1)]
            if (calendar == null || c.getInt(5) == 1) return@query null
            val local = "$calendar|${c.getString(3).orEmpty()}|${c.getLong(4)}"
            c.getLong(0) to listOfNotNull(c.getString(2)?.let { "$calendar|$it" }, local)
        }.filterNotNull().filter { ids == null || it.first in ids }.toMap()
    }

    // Every event of a calendar as import entries, so copying reuses the import with its duplicate check.
    // Changed occurrences stay attached to their series, cancelled ones become skipped dates.
    // Guests are left out, a copy must not send invitations again.
    fun copyableEvents(calendarId: Long): List<IcsEvent> {
        data class Row(
            val id: Long,
            val originalId: Long?,
            val originalTime: Long,
            val cancelled: Boolean,
            val allDay: Boolean,
            val excluded: String?,
        )
        val rows = query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.STATUS, Events.ALL_DAY, Events.EXDATE),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()),
        ) { c ->
            Row(
                id = c.getLong(0),
                originalId = if (c.isNull(1)) null else c.getLong(1),
                originalTime = c.getLong(2),
                cancelled = c.getInt(3) == Events.STATUS_CANCELED,
                allDay = c.getInt(4) == 1,
                excluded = c.getString(5)?.takeIf { it.isNotBlank() },
            )
        }

        val skipped = rows.filter { it.originalId != null && it.cancelled }.groupBy({ it.originalId!! }) { row ->
            val utc = Instant.ofEpochMilli(row.originalTime).atZone(ZoneOffset.UTC)
            if (row.allDay) utc.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).take(8)
            else utc.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        }

        return rows.filterNot { it.cancelled }.mapNotNull { row ->
            val draft = event(row.id)?.copy(id = null, attendees = emptyList()) ?: return@mapNotNull null
            if (row.originalId != null) {
                IcsEvent(draft.copy(originalId = null), uid = "copy:${row.originalId}", recurrenceId = row.originalTime)
            } else {
                val exdates = (listOfNotNull(row.excluded) + skipped[row.id].orEmpty()).joinToString(",").takeIf { it.isNotEmpty() }
                IcsEvent(draft.copy(excludedDates = exdates?.takeIf { draft.rule != null }), uid = "copy:${row.id}")
            }
        }
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
