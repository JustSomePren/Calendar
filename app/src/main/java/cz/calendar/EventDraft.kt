package cz.calendar

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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
    val priority: Priority = Priority.NORMAL,
    val watch: Watch = Watch.AUTO,
    val vacation: Vacation? = null,
    // Read only, empty for new events (they will be mine)
    val organizer: String = "",
    val attendees: List<String> = emptyList(),
    // Set when a recurring event was opened from one of its occurrences
    val instanceBegin: Long? = null,
    val seriesStart: Long? = null,
) {
    val isValid: Boolean
        get() = if (allDay) !end.toLocalDate().isBefore(start.toLocalDate()) else !end.isBefore(start)

    val timeZone: String
        get() = if (allDay) "UTC" else ZoneId.systemDefault().id

    fun startMillis(): Long = toMillis(if (allDay) start.toLocalDate().atStartOfDay() else start)

    fun endMillis(): Long = toMillis(if (allDay) end.toLocalDate().plusDays(1).atStartOfDay() else end)

    // Recurring events store a duration instead of an end time
    fun durationRule(): String =
        if (allDay) "P${Duration.ofMillis(endMillis() - startMillis()).toDays()}D"
        else "P${(endMillis() - startMillis()) / 1000}S"

    private fun toMillis(time: LocalDateTime): Long =
        time.atZone(if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun toUpcoming(calendarOwner: String) = UpcomingEvent(
        eventId = id ?: -1,
        calendarId = calendarId,
        title = title,
        location = location,
        description = description,
        organizer = organizer,
        calendarOwner = calendarOwner,
        begin = startMillis(),
        end = endMillis(),
        allDay = allDay,
        recurring = rule != null,
    )

    // Moving one occurrence moves the whole series by the same amount
    fun seriesStartMillis(): Long =
        if (instanceBegin != null && seriesStart != null) seriesStart + (startMillis() - instanceBegin) else startMillis()

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
