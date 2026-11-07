package cz.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class EventDraftTest {

    private val day = LocalDate.of(2026, 11, 6)

    @Test
    fun newDraftIsOneHourFromNine() {
        val draft = EventDraft.new(calendarId = 1, day = day)

        assertEquals(day.atTime(9, 0), draft.start)
        assertEquals(day.atTime(10, 0), draft.end)
        assertEquals("P3600S", draft.durationRule())
        assertTrue(draft.isValid)
    }

    @Test
    fun allDayIsStoredAsUtcMidnights() {
        val draft = EventDraft.new(1, day).copy(allDay = true, end = day.plusDays(2).atStartOfDay())

        assertEquals(day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), draft.startMillis())
        assertEquals(day.plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), draft.endMillis())
        assertEquals("P3D", draft.durationRule())
        assertEquals("UTC", draft.timeZone)
    }

    @Test
    fun endBeforeStartIsInvalid() {
        val draft = EventDraft.new(1, day)

        assertFalse(draft.copy(end = draft.start.minusMinutes(1)).isValid)
        assertTrue(draft.copy(allDay = true, end = draft.start.minusHours(1)).isValid)
        assertFalse(draft.copy(allDay = true, end = draft.start.minusDays(1)).isValid)
    }

    @Test
    fun customRepeatRuleIsKept() {
        val choices = repeatChoices("FREQ=WEEKLY;BYDAY=MO,WE")

        assertEquals(repeatOptions.size + 1, choices.size)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", choices.last().first)
        assertEquals(repeatOptions, repeatChoices("FREQ=DAILY"))
    }

    @Test
    fun weekdaysOfRuleMoveWithSeries() {
        assertEquals("FREQ=WEEKLY;WKST=MO;BYDAY=WE", shiftRule("FREQ=WEEKLY;WKST=MO;BYDAY=TU", 1))
        assertEquals("FREQ=WEEKLY;BYDAY=SU,TU", shiftRule("FREQ=WEEKLY;BYDAY=MO,WE", -1))
        assertEquals("FREQ=WEEKLY", shiftRule("FREQ=WEEKLY", 3))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", shiftRule("FREQ=MONTHLY;BYMONTHDAY=15", 0))
        assertEquals(null, shiftRule("FREQ=MONTHLY;BYMONTHDAY=15", 1))
        assertEquals(null, shiftRule("FREQ=MONTHLY;BYDAY=2TU", 1))
    }

    @Test
    fun movingOneOccurrenceShiftsTheSeries() {
        val draft = EventDraft.new(1, day).copy(rule = "FREQ=WEEKLY")
        val seriesStart = draft.startMillis() - 7 * 24 * 3600_000L
        val opened = draft.copy(instanceBegin = draft.startMillis(), seriesStartDate = day.minusDays(7), instanceDate = day)

        assertEquals(seriesStart, opened.seriesStartMillis())
        val moved = opened.copy(start = opened.start.plusHours(2), end = opened.end.plusHours(2))
        assertEquals(seriesStart + 2 * 3600_000L, moved.seriesStartMillis())
    }

    @Test
    fun seriesKeepsWallClockOverDaylightSavingAndAllDaySwitch() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))
        try {
            checkSeriesOverDaylightSaving()
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    private fun checkSeriesOverDaylightSaving() {
        val zone = java.time.ZoneId.systemDefault()
        val summer = LocalDate.of(2026, 7, 6)
        val winter = LocalDate.of(2026, 11, 2)
        val opened = EventDraft.new(1, winter).copy(
            rule = "FREQ=WEEKLY", allDay = true, start = winter.atStartOfDay(), end = winter.atStartOfDay(),
            instanceBegin = winter.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            seriesStartDate = summer, instanceDate = winter,
        )

        val timed = opened.copy(allDay = false, start = winter.atTime(9, 0), end = winter.atTime(10, 0))
        assertEquals(summer.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), timed.seriesStartMillis())

        val backToAllDay = timed.copy(allDay = true)
        assertEquals(summer.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), backToAllDay.seriesStartMillis())
    }
}
