package cz.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// Serializable so an unsaved draft survives rotating the phone
data class EventDraft(
    val id: Long? = null,
    val calendarId: Long,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    // For all-day events this is the last day, inclusive
    val end: LocalDateTime,
    val rule: String? = null,
    // Skipped occurrences, kept only when importing
    val excludedDates: String? = null,
    val priority: Priority = Priority.NORMAL,
    val watch: Watch = Watch.AUTO,
    val vacation: Vacation? = null,
    // Read only, empty for new events (they will be mine)
    val organizer: String = "",
    val attendees: List<String> = emptyList(),
    // Set when a recurring event was opened from one of its occurrences
    val instanceBegin: Long? = null,
    // Dates of the series start and of the opened occurrence, as they were when loaded
    val seriesStartDate: LocalDate? = null,
    val instanceDate: LocalDate? = null,
    // Set for an occurrence that was changed on its own
    val originalId: Long? = null,
    // Zone a loaded timed event is stored in, null for new events
    val eventTimeZone: String? = null,
    // Exact moments of a loaded timed event; the same clock time can happen twice when clocks go back
    val loadedBegin: Long? = null,
    val loadedEnd: Long? = null,
    val loadedSeriesStart: Long? = null,
) : java.io.Serializable {
    val isValid: Boolean
        get() = if (allDay) !end.toLocalDate().isBefore(start.toLocalDate()) else !end.isBefore(start)

    // A loaded event keeps its own zone, e.g. an invite from New York; new ones use the phone's
    private val seriesZone: ZoneId
        get() = if (allDay) ZoneOffset.UTC else eventTimeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    val timeZone: String
        get() = if (allDay) "UTC" else seriesZone.id

    fun startMillis(): Long = unchanged(start, loadedBegin) ?: toMillis(if (allDay) start.toLocalDate().atStartOfDay() else start)

    fun endMillis(): Long = unchanged(end, loadedEnd) ?: toMillis(if (allDay) end.toLocalDate().plusDays(1).atStartOfDay() else end)

    private fun unchanged(time: LocalDateTime, loaded: Long?): Long? =
        loaded?.takeIf { !allDay && Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() == time }

    // Recurring events store a duration instead of an end time
    fun durationRule(): String =
        if (allDay) "P${Duration.ofMillis(endMillis() - startMillis()).toDays()}D"
        else "P${(endMillis() - startMillis()) / 1000}S"

    private fun toMillis(time: LocalDateTime): Long =
        time.atZone(if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun toUpcoming(account: String) = UpcomingEvent(
        eventId = id ?: -1,
        calendarId = calendarId,
        title = title,
        location = location,
        description = description,
        organizer = organizer,
        account = account,
        begin = startMillis(),
        end = endMillis(),
        allDay = allDay,
        recurring = rule != null,
    )

    // Moving one occurrence moves the whole series by the same amount
    // Without repeating it simply becomes the occurrence that is shown
    // Shifted by whole days and given the new time of day, so it stays right over a daylight-saving
    // change or when switching all-day on or off
    fun seriesStartMillis(): Long {
        if (rule == null || seriesStartDate == null || instanceDate == null) return startMillis()
        // Time not touched: keep the series as it is, the occurrence may show a shifted time
        // on a daylight-saving day (02:30 does not exist and shows as 03:30)
        if (loadedSeriesStart != null && unchanged(start, loadedBegin) != null) return loadedSeriesStart
        val shown = shownInSeriesZone()
        val day = seriesStartDate.plusDays(seriesDayShift())
        val local = if (allDay) day.atStartOfDay() else day.atTime(shown.toLocalTime())
        return local.atZone(seriesZone).toInstant().toEpochMilli()
    }

    // How many days the opened occurrence was moved, the series moves the same
    fun seriesDayShift(): Long =
        if (rule == null || instanceDate == null) 0 else ChronoUnit.DAYS.between(instanceDate, shownInSeriesZone().toLocalDate())

    // Work in the series' own zone, the form shows the phone's local time
    private fun shownInSeriesZone(): LocalDateTime =
        if (allDay) start else Instant.ofEpochMilli(startMillis()).atZone(seriesZone).toLocalDateTime()

    companion object {
        fun new(calendarId: Long, day: LocalDate): EventDraft {
            val start = day.atTime(9, 0)
            return EventDraft(calendarId = calendarId, start = start, end = start.plusHours(1))
        }
    }
}

val repeatOptions = listOf(
    null to "Neopakovat",
    "FREQ=DAILY" to "Každý den",
    "FREQ=WEEKLY" to "Každý týden",
    "FREQ=MONTHLY" to "Každý měsíc",
    "FREQ=YEARLY" to "Každý rok",
)

fun repeatChoices(current: String?): List<Pair<String?, String>> =
    if (repeatOptions.any { it.first == current }) repeatOptions else repeatOptions + (current to "Vlastní opakování")

private val weekdayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

// Moves the weekdays of a rule with the series, e.g. BYDAY=TU by one day becomes BYDAY=WE.
// Returns null for rules that cannot simply move, like "every 15th" or "second Tuesday".
fun shiftRule(rule: String, days: Long): String? {
    if (days == 0L) return rule
    val parts = rule.split(';')
    val keys = parts.map { it.substringBefore('=').uppercase() }
    if (keys.any { it in listOf("BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYSETPOS") }) return null

    val shifted = parts.map { part ->
        if (!part.uppercase().startsWith("BYDAY=")) return@map part
        val codes = part.substringAfter('=').split(',').map { weekdayCodes.indexOf(it.trim().uppercase()) }
        if (codes.any { it < 0 }) return null
        "BYDAY=" + codes.joinToString(",") { weekdayCodes[Math.floorMod(it + days, 7L).toInt()] }
    }
    return shifted.joinToString(";")
}
