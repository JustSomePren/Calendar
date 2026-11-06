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

// Reads events from an .ics export (Samsung Calendar, Outlook, ...) as drafts without a calendar
fun parseIcs(text: String, zone: ZoneId = ZoneId.systemDefault()): List<EventDraft> {
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

    return events.mapNotNull { toDraft(it, zone) }
}

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

private fun unescape(text: String) =
    text.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

private fun parseTime(property: IcsProperty, zone: ZoneId): LocalDateTime? = runCatching {
    val value = property.value.trim()
    when {
        property.params["VALUE"] == "DATE" || value.length == 8 -> LocalDate.parse(value, dateFormat).atStartOfDay()
        value.endsWith("Z") ->
            LocalDateTime.parse(value.dropLast(1), dateTimeFormat).atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()
        else -> {
            val source = property.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            LocalDateTime.parse(value, dateTimeFormat).atZone(source).withZoneSameInstant(zone).toLocalDateTime()
        }
    }
}.getOrNull()

private fun toDraft(properties: List<IcsProperty>, zone: ZoneId): EventDraft? {
    val byName = properties.associateBy { it.name }
    // Changed occurrences and cancelled events are left out
    if ("RECURRENCE-ID" in byName || byName["STATUS"]?.value.equals("CANCELLED", ignoreCase = true)) return null

    val startProperty = byName["DTSTART"] ?: return null
    val start = parseTime(startProperty, zone) ?: return null
    val allDay = startProperty.params["VALUE"] == "DATE" || startProperty.value.trim().length == 8

    val end = byName["DTEND"]?.let { parseTime(it, zone) }
        ?: byName["DURATION"]?.let { start.plus(parseDuration(it.value)) }
        ?: if (allDay) start.plusDays(1) else start

    return EventDraft(
        calendarId = -1,
        title = unescape(byName["SUMMARY"]?.value.orEmpty()),
        location = unescape(byName["LOCATION"]?.value.orEmpty()),
        description = unescape(byName["DESCRIPTION"]?.value.orEmpty()),
        allDay = allDay,
        start = start,
        // All-day ends are exclusive in .ics, drafts keep the last day
        end = if (allDay) maxOf(start, end.minus(Duration.ofDays(1))) else end,
        rule = byName["RRULE"]?.value?.takeIf { it.isNotBlank() },
    )
}

// Saves drafts into a calendar, skipping ones that are already there; returns how many were added
fun CalendarRepository.import(drafts: List<EventDraft>, calendarId: Long): Int =
    drafts.map { it.copy(calendarId = calendarId) }
        .filterNot { exists(calendarId, it.title, it.startMillis()) }
        .onEach { saveEvent(it) }
        .size
