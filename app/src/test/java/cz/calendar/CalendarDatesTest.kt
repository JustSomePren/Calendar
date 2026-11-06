package cz.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarDatesTest {

    private val prague = ZoneId.of("Europe/Prague")

    private fun millis(time: LocalDateTime, zone: ZoneId) = time.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun gridStartsOnMondayAndCoversMonth() {
        val month = YearMonth.of(2026, 11)
        val days = gridDays(month)

        assertEquals(42, days.size)
        assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 26), days.first())
        assertTrue(month.atEndOfMonth() in days)
    }

    @Test
    fun monthTitleIsCzech() {
        assertEquals("Listopad 2026", monthTitle(YearMonth.of(2026, 11)))
    }

    @Test
    fun allDayEventCoversOnlyItsDay() {
        val begin = millis(LocalDate.of(2026, 11, 6).atStartOfDay(), ZoneOffset.UTC)
        val end = millis(LocalDate.of(2026, 11, 7).atStartOfDay(), ZoneOffset.UTC)

        val days = eventDays(begin, end, ZoneOffset.UTC)

        assertEquals(LocalDate.of(2026, 11, 6), days.start)
        assertEquals(LocalDate.of(2026, 11, 6), days.endInclusive)
    }

    @Test
    fun timedEventOverMidnightCoversTwoDays() {
        val begin = millis(LocalDateTime.of(2026, 11, 6, 22, 0), prague)
        val end = millis(LocalDateTime.of(2026, 11, 7, 1, 0), prague)

        val days = eventDays(begin, end, prague)

        assertEquals(LocalDate.of(2026, 11, 6), days.start)
        assertEquals(LocalDate.of(2026, 11, 7), days.endInclusive)
    }

    @Test
    fun eventEndingAtMidnightStaysOnOneDay() {
        val begin = millis(LocalDateTime.of(2026, 11, 6, 20, 0), prague)
        val end = millis(LocalDateTime.of(2026, 11, 7, 0, 0), prague)

        assertEquals(LocalDate.of(2026, 11, 6), eventDays(begin, end, prague).endInclusive)
    }

    @Test
    fun zeroLengthEventHasOneDay() {
        val time = millis(LocalDateTime.of(2026, 11, 6, 12, 0), prague)
        val days = eventDays(time, time, prague)

        assertEquals(days.start, days.endInclusive)
    }

    @Test
    fun fitIntoLeavesRoomForMoreLine() {
        val items = listOf(1, 2, 3, 4, 5)

        assertEquals(listOf(1, 2, 3, 4, 5), items.fitInto(5))
        assertEquals(listOf(1, 2, 3), items.fitInto(4))
        assertEquals(emptyList<Int>(), items.fitInto(0))
        assertEquals(emptyList<Int>(), items.fitInto(-2))
    }

    @Test
    fun parsesProviderDurations() {
        assertEquals(Duration.ofHours(1), parseDuration("P3600S"))
        assertEquals(Duration.ofHours(1), parseDuration("PT1H"))
        assertEquals(Duration.ofDays(1), parseDuration("P1D"))
        assertEquals(Duration.ofDays(14), parseDuration("P2W"))
        assertEquals(Duration.ofMinutes(90), parseDuration("PT1H30M"))
        assertEquals(Duration.ZERO, parseDuration(null))
        assertEquals(Duration.ZERO, parseDuration("nonsense"))
    }
}
