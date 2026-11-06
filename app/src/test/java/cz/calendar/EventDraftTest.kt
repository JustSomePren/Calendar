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
    fun reminderLabelsAreReadable() {
        assertEquals("Bez upozornění", reminderLabel(null))
        assertEquals("15 min předem", reminderLabel(15))
        assertEquals("2 h předem", reminderLabel(120))
        assertEquals("1 den předem", reminderLabel(1440))
        assertEquals("2 dny předem", reminderLabel(2880))
    }

    @Test
    fun unusualReminderIsAddedToChoices() {
        val choices = reminderChoices(15).map { it.first }

        assertEquals(listOf(null, 0, 10, 15, 30, 60, 1440), choices)
    }
}
