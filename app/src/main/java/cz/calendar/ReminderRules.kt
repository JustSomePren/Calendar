package cz.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class UpcomingEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String = "",
    val description: String = "",
    val organizer: String = "",
    val begin: Long,
    val end: Long,
    val allDay: Boolean = false,
    val recurring: Boolean = false,
)

private val eveningBefore = LocalTime.of(20, 0)
private val dayBefore = LocalTime.of(18, 0)
private val sameMorning = LocalTime.of(7, 30)
private val allDayMorning = LocalTime.of(8, 0)
private val morningEnds = LocalTime.of(9, 0)

/*
 * When to remind about an event:
 * - multi-day: 3 days before and the day before at 18:00
 * - all-day: the day before at 18:00
 * - early morning (before 9:00): the evening before at 20:00 and 30 min before
 * - forenoon (before 12:00): the same morning at 7:30 and 30 min before
 * - anything else: 30 min before
 * Recurring events and low priority keep only the closest reminder.
 * High priority adds an earlier one and, for all-day events, a morning one.
 */
fun reminderTimes(event: UpcomingEvent, priority: Priority, zone: ZoneId): List<Long> {
    if (priority == Priority.NONE) return emptyList()

    val days = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else zone)
    val startDay = days.start
    val multiDay = days.endInclusive > startDay
    val startTime = Instant.ofEpochMilli(event.begin).atZone(zone).toLocalTime()

    fun at(day: LocalDate, time: LocalTime) = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
    fun minutesBefore(minutes: Long) = event.begin - minutes * 60_000

    var times = when {
        multiDay -> listOf(at(startDay.minusDays(3), dayBefore), at(startDay.minusDays(1), dayBefore))
        event.allDay -> listOf(at(startDay.minusDays(1), dayBefore))
        startTime < morningEnds -> listOf(at(startDay.minusDays(1), eveningBefore), minutesBefore(30))
        startTime < LocalTime.NOON -> listOf(at(startDay, sameMorning), minutesBefore(30))
        else -> listOf(minutesBefore(30))
    }

    if (event.recurring || priority == Priority.LOW) times = times.takeLast(1)
    if (priority == Priority.HIGH) {
        times = times + if (event.allDay || multiDay) {
            listOf(at(startDay, allDayMorning))
        } else {
            listOf(minutesBefore(120), at(startDay.minusDays(1), eveningBefore))
        }
    }

    // All-day events may still remind on their own morning
    val deadline = if (event.allDay || multiDay) event.end else event.begin
    return times.filter { it < deadline }.distinct().sorted()
}

fun matchesWorkFilter(
    event: UpcomingEvent,
    attendees: List<Attendee>,
    emails: List<String>,
    keywords: List<String>,
): Boolean {
    val byEmail = emails.any { email ->
        val mine = attendees.filter { it.email.equals(email, ignoreCase = true) }
        mine.none { it.declined } && (mine.isNotEmpty() || event.organizer.equals(email, ignoreCase = true))
    }
    val texts = listOf(event.title, event.description, event.location)
    val byText = keywords.any { keyword -> texts.any { it.contains(keyword, ignoreCase = true) } }
    return byEmail || byText
}

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d. M.", czech)
private val timeFormat = DateTimeFormatter.ofPattern("H:mm")

fun dayLabel(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
    0L -> "Dnes"
    1L -> "Zítra"
    2L -> "Pozítří"
    else -> day.format(dayFormat).capitalized()
}

fun whenText(event: UpcomingEvent, now: Long, zone: ZoneId): String {
    val days = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val minutesLeft = (event.begin - now) / 60_000
    val time = Instant.ofEpochMilli(event.begin).atZone(zone).format(timeFormat)

    return when {
        days.endInclusive > days.start ->
            "${dayLabel(days.start, today)} – ${dayLabel(days.endInclusive, today).replaceFirstChar { it.lowercase() }}"
        event.allDay -> "${dayLabel(days.start, today)}, celý den"
        minutesLeft in 0..59 -> "Za $minutesLeft min, v $time"
        else -> "${dayLabel(days.start, today)} v $time"
    }
}
