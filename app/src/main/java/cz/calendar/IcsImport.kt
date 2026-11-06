package cz.calendar

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

private data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

// An occurrence moved in the export carries the start it had in its series
data class IcsEvent(val draft: EventDraft, val uid: String = "", val recurrenceId: Long? = null)

// Reads events from an .ics export (Samsung Calendar, Outlook, ...), not yet tied to a calendar
fun parseIcs(text: String, zone: ZoneId = ZoneId.systemDefault()): List<IcsEvent> {
    val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").lines()

    val events = mutableListOf<List<IcsProperty>>()
    var current: MutableList<IcsProperty>? = null
    var nested = 0
    for (line in lines) {
        when {
            line == "BEGIN:VEVENT" -> current = mutableListOf()
            line == "END:VEVENT" -> current?.let { events += it }.also { current = null }
            line.startsWith("BEGIN:") && current != null -> nested++
            line.startsWith("END:") && current != null -> nested--
            current != null && nested == 0 -> parseProperty(line)?.let { current?.add(it) }
        }
    }

    // An occurrence deleted in the export comes as a cancelled event pointing at its series
    val cancelled = events
        .filter { event -> event.any { it.name == "STATUS" && it.value.equals("CANCELLED", ignoreCase = true) } }
        .flatMap { event -> event.filter { it.name == "RECURRENCE-ID" }.map { uidOf(event) to it } }
        .groupBy({ it.first }, { it.second })

    return events.mapNotNull { toEvent(it, cancelled[uidOf(it)].orEmpty(), zone) }
}

private fun uidOf(event: List<IcsProperty>) = event.firstOrNull { it.name == "UID" }?.value.orEmpty()

private fun parseProperty(line: String): IcsProperty? {
    var quoted = false
    val colon = line.indexOfFirst { c ->
        if (c == '"') quoted = !quoted
        c == ':' && !quoted
    }
    if (colon < 0) return null

    val head = line.substring(0, colon).split(';')
    val params = head.drop(1).mapNotNull { param ->
        param.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key.uppercase() to value.trim('"') }
    }.toMap()
    return IcsProperty(head.first().uppercase(), params, line.substring(colon + 1))
}

// One pass from the left, so an escaped backslash before "n" stays a backslash
private fun unescape(text: String) =
    Regex("""\\(.)""").replace(text) { if (it.groupValues[1] in listOf("n", "N")) "\n" else it.groupValues[1] }

private fun isDate(property: IcsProperty, value: String = property.value.trim()) =
    property.params["VALUE"] == "DATE" || value.length == 8

// Local time in the phone's zone; dates become midnight
private fun parseTime(property: IcsProperty, zone: ZoneId, value: String = property.value.trim()): LocalDateTime? = runCatching {
    when {
        isDate(property, value) -> LocalDate.parse(value, dateFormat).atStartOfDay()
        value.endsWith("Z") ->
            LocalDateTime.parse(value.dropLast(1), dateTimeFormat).atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()
        else -> {
            val source = property.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            LocalDateTime.parse(value, dateTimeFormat).atZone(source).withZoneSameInstant(zone).toLocalDateTime()
        }
    }
}.getOrNull()

private fun fileZone(property: IcsProperty): String? = when {
    property.value.trim().endsWith("Z") -> "UTC"
    else -> property.params["TZID"]?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
}

// Moment as the calendar provider stores it, all-day dates are midnight UTC
private fun parseMillis(property: IcsProperty, zone: ZoneId, value: String = property.value.trim()): Long? {
    val time = parseTime(property, zone, value) ?: return null
    return time.atZone(if (isDate(property, value)) ZoneOffset.UTC else zone).toInstant().toEpochMilli()
}

// Skipped occurrences in the provider's EXDATE format
private fun excludedDates(dates: List<IcsProperty>, zone: ZoneId): String? =
    dates
        .flatMap { property -> property.value.split(',').map { property to it.trim() } }
        .mapNotNull { (property, value) ->
            val millis = parseMillis(property, zone, value) ?: return@mapNotNull null
            val utc = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
            if (isDate(property, value)) utc.format(dateFormat) else utc.format(dateTimeFormat) + "Z"
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(",")

private fun toEvent(properties: List<IcsProperty>, cancelledOccurrences: List<IcsProperty>, zone: ZoneId): IcsEvent? {
    val byName = properties.associateBy { it.name }
    if (byName["STATUS"]?.value.equals("CANCELLED", ignoreCase = true)) return null

    val startProperty = byName["DTSTART"] ?: return null
    val start = parseTime(startProperty, zone) ?: return null
    val allDay = isDate(startProperty)

    val end = byName["DTEND"]?.let { parseTime(it, zone) }
        ?: byName["DURATION"]?.let { start.plus(parseDuration(it.value)) }
        ?: if (allDay) start.plusDays(1) else start
    val rule = byName["RRULE"]?.value?.takeIf { it.isNotBlank() }

    val draft = EventDraft(
        calendarId = -1,
        title = unescape(byName["SUMMARY"]?.value.orEmpty()),
        location = unescape(byName["LOCATION"]?.value.orEmpty()),
        description = unescape(byName["DESCRIPTION"]?.value.orEmpty()),
        allDay = allDay,
        start = start,
        // All-day ends are exclusive in .ics, drafts keep the last day
        end = if (allDay) maxOf(start, end.minus(Duration.ofDays(1))) else end,
        rule = rule,
        excludedDates = rule?.let { excludedDates(properties.filter { it.name == "EXDATE" } + cancelledOccurrences, zone) },
        // The series repeats in the file's own zone, e.g. 10:00 New York all year round
        eventTimeZone = if (allDay) null else fileZone(startProperty),
    )
    return IcsEvent(draft, byName["UID"]?.value.orEmpty(), byName["RECURRENCE-ID"]?.let { parseMillis(it, zone) })
}

// Saves events into a calendar, skipping ones that are already there; returns how many were added
fun CalendarRepository.import(events: List<IcsEvent>, calendarId: Long): Int {
    val series = HashMap<String, Long>()
    var added = 0
    // Series go first, so their changed occurrences can be attached to them
    for (event in events.sortedBy { it.recurrenceId != null }) {
        val draft = event.draft.copy(calendarId = calendarId)
        val seriesId = series[event.uid]
        val id = if (event.recurrenceId != null && seriesId != null) {
            // A changed occurrence may start just like its series, so look among the series' exceptions
            findException(seriesId, event.recurrenceId)
                ?: saveOccurrence(draft.copy(id = seriesId, instanceBegin = event.recurrenceId)).also { added++ }
        } else {
            findEvent(calendarId, draft.title, draft.startMillis()) ?: saveEvent(draft).also { added++ }
        }
        if (event.recurrenceId == null && draft.rule != null && event.uid.isNotEmpty()) series[event.uid] = id
    }
    return added
}
