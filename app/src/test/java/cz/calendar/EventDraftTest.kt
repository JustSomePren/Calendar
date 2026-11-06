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
    fun movingOneOccurrenceShiftsTheSeries() {
        val draft = EventDraft.new(1, day)
        val seriesStart = draft.startMillis() - 7 * 24 * 3600_000L
        val opened = draft.copy(instanceBegin = draft.startMillis(), seriesStart = seriesStart)

        assertEquals(seriesStart, opened.seriesStartMillis())
        val moved = opened.copy(start = opened.start.plusHours(2), end = opened.end.plusHours(2))
        assertEquals(seriesStart + 2 * 3600_000L, moved.seriesStartMillis())
    }
}
