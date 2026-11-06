package cz.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

val czech: Locale = Locale.forLanguageTag("cs")
val weekdays = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

fun gridDays(month: YearMonth): List<LocalDate> {
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return List(42) { start.plusDays(it.toLong()) }
}

fun monthTitle(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", czech)).capitalized()

fun String.capitalized() = replaceFirstChar { it.titlecase(czech) }

// Days covered by an event, end is exclusive like in the calendar provider
fun eventDays(begin: Long, end: Long, zone: ZoneId): ClosedRange<LocalDate> {
    val first = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
    return first..last
}

// RFC 2445 durations as used by the calendar provider, e.g. P3600S, PT1H, P1D, P1W
fun parseDuration(text: String?): Duration {
    val match = Regex("""[+-]?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(text.orEmpty().trim())
        ?: return Duration.ZERO
    val (weeks, days, hours, minutes, seconds) = match.destructured.toList().map { it.toLongOrNull() ?: 0 }
    return Duration.ofDays(weeks * 7 + days).plusHours(hours).plusMinutes(minutes).plusSeconds(seconds)
}
