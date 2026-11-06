package cz.calendar

import java.time.Duration
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
) : java.io.Serializable {
    val isValid: Boolean
        get() = if (allDay) !end.toLocalDate().isBefore(start.toLocalDate()) else !end.isBefore(start)

    // A loaded event keeps its own zone, e.g. an invite from New York; new ones use the phone's
    private val seriesZone: ZoneId
        get() = if (allDay) ZoneOffset.UTC else eventTimeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    val timeZone: String
        get() = if (allDay) "UTC" else seriesZone.id

    fun startMillis(): Long = toMillis(if (allDay) start.toLocalDate().atStartOfDay() else start)

    fun endMillis(): Long = toMillis(if (allDay) end.toLocalDate().plusDays(1).atStartOfDay() else end)

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
        // Work in the series' own zone, the form shows the phone's local time
        val shown = if (allDay) start else start.atZone(ZoneId.systemDefault()).withZoneSameInstant(seriesZone).toLocalDateTime()
        val day = seriesStartDate.plusDays(ChronoUnit.DAYS.between(instanceDate, shown.toLocalDate()))
        val local = if (allDay) day.atStartOfDay() else day.atTime(shown.toLocalTime())
        return local.atZone(seriesZone).toInstant().toEpochMilli()
    }

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
