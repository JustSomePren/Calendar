package cz.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderRulesTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val day = LocalDate.of(2026, 11, 10)

    private fun millis(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()
    private fun times(list: List<Long>) = list.map { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }

    private fun timed(hour: Int, minute: Int = 0, recurring: Boolean = false) = UpcomingEvent(
        eventId = 1, calendarId = 1, title = "Schůzka",
        begin = millis(day.atTime(hour, minute)), end = millis(day.atTime(hour + 1, minute)),
        recurring = recurring,
    )

    private fun allDay(days: Long) = UpcomingEvent(
        eventId = 1, calendarId = 1, title = "Dovolená", allDay = true,
        begin = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        end = day.plusDays(days).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    @Test
    fun earlyMorningRemindsTheEveningBefore() {
        assertEquals(
            listOf(day.minusDays(1).atTime(20, 0), day.atTime(7, 30)),
            times(reminderTimes(timed(8), Priority.NORMAL, zone)),
        )
    }

    @Test
    fun forenoonRemindsTheSameMorning() {
        assertEquals(
            listOf(day.atTime(7, 30), day.atTime(10, 0)),
            times(reminderTimes(timed(10, 30), Priority.NORMAL, zone)),
        )
    }

    @Test
    fun afternoonRemindsHalfHourBefore() {
        assertEquals(listOf(day.atTime(14, 30)), times(reminderTimes(timed(15), Priority.NORMAL, zone)))
    }

    @Test
    fun nightShiftIsNotMultiDay() {
        val shift = timed(22).copy(end = millis(day.plusDays(1).atTime(6, 0)))
        assertEquals(listOf(day.atTime(21, 30)), times(reminderTimes(shift, Priority.NORMAL, zone)))
        assertEquals("Zítra v 22:00", whenText(shift, millis(day.minusDays(1).atTime(20, 0)), zone))
    }

    @Test
    fun highPriorityTripRemindsBeforeDeparture() {
        val trip = timed(6).copy(end = millis(day.plusDays(2).atTime(20, 0)))

        assertEquals(
            listOf(
                day.minusDays(3).atTime(18, 0),
                day.minusDays(1).atTime(18, 0),
                day.minusDays(1).atTime(20, 0),
                day.atTime(4, 0),
            ),
            times(reminderTimes(trip, Priority.HIGH, zone)),
        )
    }

    @Test
    fun dayLongShiftOverDaylightSavingNightIsMultiDay() {
        val march = UpcomingEvent(
            eventId = 1, calendarId = 1, title = "Služba 24h",
            begin = millis(LocalDateTime.of(2027, 3, 27, 7, 0)), end = millis(LocalDateTime.of(2027, 3, 28, 7, 0)),
        )
        assertEquals(
            listOf(LocalDateTime.of(2027, 3, 24, 18, 0), LocalDateTime.of(2027, 3, 26, 18, 0)),
            times(reminderTimes(march, Priority.NORMAL, zone)),
        )

        val october = march.copy(
            begin = millis(LocalDateTime.of(2026, 10, 24, 7, 0)), end = millis(LocalDateTime.of(2026, 10, 25, 6, 30)),
        )
        assertFalse(october.isMultiDay(zone))
    }

    @Test
    fun allDayRemindsTheDayBefore() {
        assertEquals(listOf(day.minusDays(1).atTime(18, 0)), times(reminderTimes(allDay(1), Priority.NORMAL, zone)))
    }

    @Test
    fun multiDayRemindsEarlier() {
        assertEquals(
            listOf(day.minusDays(3).atTime(18, 0), day.minusDays(1).atTime(18, 0)),
            times(reminderTimes(allDay(4), Priority.NORMAL, zone)),
        )
    }

    @Test
    fun recurringKeepsOnlyTheClosest() {
        assertEquals(listOf(day.atTime(7, 30)), times(reminderTimes(timed(8, recurring = true), Priority.NORMAL, zone)))
    }

    @Test
    fun lowPriorityKeepsOnlyTheClosest() {
        assertEquals(listOf(day.minusDays(1).atTime(18, 0)), times(reminderTimes(allDay(4), Priority.LOW, zone)))
    }

    @Test
    fun highPriorityAddsEarlierReminders() {
        assertEquals(
            listOf(day.minusDays(1).atTime(20, 0), day.atTime(13, 0), day.atTime(14, 30)),
            times(reminderTimes(timed(15), Priority.HIGH, zone)),
        )
        assertEquals(
            listOf(day.minusDays(1).atTime(18, 0), day.atTime(8, 0)),
            times(reminderTimes(allDay(1), Priority.HIGH, zone)),
        )
    }

    @Test
    fun categoryAndWatchDecideWhetherToNotify() {
        val never = { error("Filter should not be needed") }

        assertTrue(shouldNotify(Category.PERSONAL, Watch.AUTO, mine = false, never))
        assertFalse(shouldNotify(Category.PERSONAL, Watch.NEVER, mine = true, never))
        assertFalse(shouldNotify(Category.QUIET, Watch.AUTO, mine = true, never))
        assertTrue(shouldNotify(Category.QUIET, Watch.ALWAYS, mine = false, never))
        assertTrue(shouldNotify(Category.WORK, Watch.AUTO, mine = true, never))
        assertTrue(shouldNotify(Category.WORK, Watch.ALWAYS, mine = false, never))
        assertTrue(shouldNotify(Category.WORK, Watch.AUTO, mine = false) { true })
        assertFalse(shouldNotify(Category.WORK, Watch.AUTO, mine = false) { false })
    }

    @Test
    fun eventIsMineWhenICreatedIt() {
        assertTrue(timed(15).copy(organizer = "Ja@Firma.cz", account = "ja@firma.cz").isMine)
        assertTrue(timed(15).copy(organizer = "", account = "ja@firma.cz").isMine)
        assertFalse(timed(15).copy(organizer = "sef@firma.cz", account = "ja@firma.cz").isMine)
    }

    @Test
    fun workFilterMatchesMyEmailOrKeyword() {
        val event = timed(15).copy(title = "Sprint testProjekt")
        val me = "ja@firma.cz"

        assertTrue(matchesWorkFilter(event, emptyList(), emptyList(), listOf("testprojekt")))
        assertTrue(matchesWorkFilter(timed(15), listOf(Attendee("JA@firma.cz", 1)), listOf(me), emptyList()))
        assertTrue(matchesWorkFilter(timed(15).copy(organizer = me), emptyList(), listOf(me), emptyList()))
        assertFalse(matchesWorkFilter(timed(15), listOf(Attendee("kolega@firma.cz", 1)), listOf(me), listOf("testProjekt")))
        assertFalse(matchesWorkFilter(timed(15), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun declinedEventIsNotMine() {
        val declined = android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        val event = timed(15).copy(organizer = "sef@firma.cz")
        assertFalse(matchesWorkFilter(event, listOf(Attendee("ja@firma.cz", declined)), listOf("ja@firma.cz"), emptyList()))
    }

    @Test
    fun whenTextIsHuman() {
        val now = millis(day.atTime(14, 40))
        assertEquals("Za 20 min, v 15:00", whenText(timed(15), now, zone))
        assertEquals("Zítra v 15:00", whenText(timed(15), millis(day.minusDays(1).atTime(18, 0)), zone))
        assertEquals("Zítra, celý den", whenText(allDay(1), millis(day.minusDays(1).atTime(18, 0)), zone))
        assertEquals("Pozítří – čtvrtek 12. 11.", whenText(allDay(3), millis(day.minusDays(2).atTime(18, 0)), zone))
    }
}
