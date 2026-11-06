package cz.calendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RepositoryTest {

    private lateinit var repository: CalendarRepository
    private val day = LocalDate.now().plusDays(3)

    @Before
    fun setUp() {
        val app = setUpFakeCalendar()
        FakeCalendarProvider.addCalendar(1, "Osobní")
        FakeCalendarProvider.addCalendar(2, "Svátky", writable = false)
        repository = CalendarRepository(app.contentResolver)
    }

    @Test
    fun readsCalendars() {
        val calendars = repository.calendars()
        assertEquals(listOf("Osobní", "Svátky"), calendars.map { it.name })
        assertEquals(listOf(true, false), calendars.map { it.writable })
    }

    @Test
    fun savesNewEventWithAttendees() {
        val draft = EventDraft.new(1, day).copy(title = " Zubař ", attendees = listOf("Ja@Firma.cz"))
        val id = repository.saveEvent(draft)

        val row = FakeCalendarProvider.event(id)
        assertEquals("Zubař", row[Events.TITLE])
        assertEquals(draft.startMillis(), row[Events.DTSTART])
        assertEquals(draft.endMillis(), row[Events.DTEND])
        assertEquals(ZoneId.systemDefault().id, row[Events.EVENT_TIMEZONE])
        assertNull(row[Events.RRULE])
        assertEquals(listOf("ja@firma.cz"), repository.attendees(id).map { it.email })
    }

    @Test
    fun recurringEventStoresDuration() {
        val id = repository.saveEvent(EventDraft.new(1, day).copy(rule = "FREQ=WEEKLY"))

        val row = FakeCalendarProvider.event(id)
        assertEquals("FREQ=WEEKLY", row[Events.RRULE])
        assertEquals("P3600S", row[Events.DURATION])
        assertNull(row[Events.DTEND])
    }

    @Test
    fun loadsEventBack() {
        val id = repository.saveEvent(EventDraft.new(1, day).copy(title = "Zubař", location = "Praha", allDay = true))

        val loaded = repository.event(id)!!
        assertEquals("Zubař", loaded.title)
        assertEquals("Praha", loaded.location)
        assertEquals(true, loaded.allDay)
        assertEquals(day, loaded.start.toLocalDate())
        assertEquals(day, loaded.end.toLocalDate())
    }

    @Test
    fun editingOccurrenceMovesWholeSeriesBySameAmount() {
        val seriesId = repository.saveEvent(EventDraft.new(1, day).copy(rule = "FREQ=WEEKLY"))
        val seriesStart = FakeCalendarProvider.event(seriesId)[Events.DTSTART] as Long
        val occurrence = seriesStart + 7 * 24 * 3600_000L

        val opened = repository.event(seriesId, occurrence)!!
        assertEquals(day.plusDays(7).atTime(9, 0), opened.start)

        repository.saveEvent(opened.copy(start = opened.start.plusHours(1), end = opened.end.plusHours(1)))
        assertEquals(seriesStart + 3600_000L, FakeCalendarProvider.event(seriesId)[Events.DTSTART])
    }

    @Test
    fun savingOneOccurrenceCreatesException() {
        val seriesId = repository.saveEvent(EventDraft.new(1, day).copy(rule = "FREQ=DAILY", title = "Standup"))
        val occurrence = (FakeCalendarProvider.event(seriesId)[Events.DTSTART] as Long) + 24 * 3600_000L

        val opened = repository.event(seriesId, occurrence)!!
        val exceptionId = repository.saveOccurrence(opened.copy(title = "Standup jinde"))

        val exception = FakeCalendarProvider.event(exceptionId)
        assertEquals(occurrence, exception[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals("Standup jinde", exception[Events.TITLE])
        assertEquals("Standup", FakeCalendarProvider.event(seriesId)[Events.TITLE])
    }

    @Test
    fun removesAttendees() {
        val id = repository.saveEvent(EventDraft.new(1, day).copy(attendees = listOf("a@x.cz", "b@x.cz")))
        repository.saveEvent(repository.event(id)!!.copy(attendees = listOf("b@x.cz")))

        assertEquals(listOf("b@x.cz"), repository.attendees(id).map { it.email })
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, repository.attendees(id).single().status)
    }

    @Test
    fun importSkipsDuplicates() {
        val ics = "BEGIN:VEVENT\r\nSUMMARY:Zubař\r\nDTSTART:${day.toString().replace("-", "")}T090000\r\nEND:VEVENT"

        assertEquals(1, repository.import(parseIcs(ics), 1))
        assertEquals(0, repository.import(parseIcs(ics), 1))
        assertEquals(1, FakeCalendarProvider.events.size)
    }

    @Test
    fun spreadsMultiDayEventsAcrossDays() {
        repository.saveEvent(EventDraft.new(1, day).copy(title = "Hory", allDay = true, end = day.plusDays(2).atStartOfDay()))

        val events = repository.eventsByDay(day.minusDays(1), day.plusDays(3), hidden = emptySet())
        assertEquals(listOf(day, day.plusDays(1), day.plusDays(2)), events.keys.sorted())
        assertEquals(true, events.getValue(day).single().multiDay)
    }
}
