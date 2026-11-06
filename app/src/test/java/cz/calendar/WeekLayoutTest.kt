package cz.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class WeekLayoutTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val monday = LocalDate.of(2026, 11, 9)
    private val week = List(7) { monday.plusDays(it.toLong()) }

    private fun allDay(id: Long, from: LocalDate, days: Long) = CalendarEvent(
        eventId = id, calendarId = 1, title = "A$id", allDay = true, multiDay = days > 1, color = 0,
        begin = from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        end = from.plusDays(days).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    private fun timed(id: Long, day: LocalDate, hour: Int) = CalendarEvent(
        eventId = id, calendarId = 1, title = "T$id", allDay = false, multiDay = false, color = 0,
        begin = LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli(),
        end = LocalDateTime.of(day, java.time.LocalTime.of(hour + 1, 0)).atZone(zone).toInstant().toEpochMilli(),
    )

    private fun byDay(vararg events: Pair<CalendarEvent, List<LocalDate>>): Map<LocalDate, List<CalendarEvent>> {
        val map = HashMap<LocalDate, MutableList<CalendarEvent>>()
        events.forEach { (event, days) -> days.forEach { map.getOrPut(it) { mutableListOf() }.add(event) } }
        return map
    }

    @Test
    fun multiDayEventIsOneBar() {
        val trip = allDay(1, monday.plusDays(1), 3)
        val layout = layoutWeek(week, byDay(trip to week.subList(1, 4)), slots = 4, zone = zone)

        assertEquals(1, layout.bars.size)
        val bar = layout.bars.single()
        assertEquals(1, bar.fromCol)
        assertEquals(3, bar.toCol)
        assertEquals(true, bar.startsHere)
        assertEquals(true, bar.endsHere)
    }

    @Test
    fun barContinuingFromLastWeekDoesNotStartHere() {
        val trip = allDay(1, monday.minusDays(2), 4)
        val layout = layoutWeek(week, byDay(trip to week.subList(0, 2)), slots = 4, zone = zone)

        assertEquals(false, layout.bars.single().startsHere)
        assertEquals(true, layout.bars.single().endsHere)
    }

    @Test
    fun overlappingBarsGetDifferentRowsAndTimedGoBelow() {
        val a = allDay(1, monday, 3)
        val b = allDay(2, monday.plusDays(2), 2)
        val meeting = timed(3, monday.plusDays(2), 10)
        val layout = layoutWeek(
            week,
            byDay(a to week.subList(0, 3), b to week.subList(2, 4), meeting to listOf(week[2])),
            slots = 5,
            zone = zone,
        )

        assertEquals(0, layout.bars.first { it.event == a }.row)
        assertEquals(1, layout.bars.first { it.event == b }.row)
        assertEquals(listOf(2 to meeting), layout.singles[2])
    }

    @Test
    fun overflowShowsMoreCount() {
        val events = (1L..5L).map { timed(it, monday, 8 + it.toInt()) }
        val layout = layoutWeek(week, mapOf(monday to events), slots = 3, zone = zone)

        assertEquals(2, layout.singles[0].size)
        assertEquals(3, layout.hidden[0])
        assertEquals(2, layout.moreRow[0])
    }

    @Test
    fun barIsCutWhereADayOverflows() {
        val trip = allDay(1, monday, 3)
        val busy = (1L..3L).map { timed(10 + it, monday.plusDays(1), 8 + it.toInt()) }
        val events = byDay(trip to week.subList(0, 3)).toMutableMap()
        events[monday.plusDays(1)] = events.getValue(monday.plusDays(1)) + busy
        val layout = layoutWeek(week, events, slots = 2, zone = zone)

        // Row 0 is still visible everywhere, so the bar stays whole and the busy day shows "+3"
        assertEquals(1, layout.bars.size)
        assertEquals(0 to 2, layout.bars.single().fromCol to layout.bars.single().toCol)
        assertEquals(0, layout.singles[1].size)
        assertEquals(3, layout.hidden[1])
        assertEquals(1, layout.moreRow[1])
    }

    @Test
    fun barSplitsWhenItsRowIsHiddenOnOneDay() {
        val first = allDay(1, monday, 3)
        val second = allDay(2, monday, 3)
        val busy = (1L..2L).map { timed(10 + it, monday.plusDays(1), 8 + it.toInt()) }
        val events = byDay(first to week.subList(0, 3), second to week.subList(0, 3)).toMutableMap()
        events[monday.plusDays(1)] = events.getValue(monday.plusDays(1)) + busy
        val layout = layoutWeek(week, events, slots = 2, zone = zone)

        val pieces = layout.bars.filter { it.event == second }.map { it.fromCol to it.toCol }
        assertEquals(listOf(0 to 0, 2 to 2), pieces)
        assertEquals(false, layout.bars.first { it.event == second && it.fromCol == 2 }.startsHere)
    }
}
