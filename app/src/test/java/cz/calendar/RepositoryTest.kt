package cz.calendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun listsCalendarsNotSyncedYetAndSwitchesThemOn() {
        FakeCalendarProvider.addCalendar(3, "Tým", account = "ja@firma.cz", synced = false)

        val team = repository.calendars().single { it.name == "Tým" }
        assertEquals(false, team.synced)

        repository.startSyncing(team)
        assertEquals(true, repository.calendars().single { it.name == "Tým" }.synced)
        val account = android.accounts.Account("ja@firma.cz", "com.google")
        assertTrue(org.robolectric.shadows.ShadowContentResolver.getStatus(account, android.provider.CalendarContract.AUTHORITY).syncRequests > 0)
    }

    @Test
    fun createsCalendarKeptOnlyOnPhone() {
        val id = repository.createLocalCalendar("Domácnost", 0xFFE67C73.toInt())

        val calendar = repository.calendars().single { it.id == id }
        assertEquals("Domácnost", calendar.name)
        assertEquals(LOCAL_ACCOUNT, calendar.account)
        assertTrue(calendar.local && calendar.writable && calendar.synced)
    }

    @Test
    fun ordersDayByPersonalThenMyWorkThenWorkThenRest() {
        FakeCalendarProvider.addCalendar(3, "Práce", account = "ja@firma.cz")
        FakeCalendarProvider.addCalendar(4, "Svátky")
        val categories = mapOf(3L to Category.WORK, 4L to Category.QUIET)
        val ranking = Ranking({ categories[it] ?: Category.PERSONAL }, { false }, listOf("ja@firma.cz"), listOf("testProjekt"))
        fun add(title: String, calendar: Long, hour: Int, extra: Map<String, Any?> = emptyMap()) = FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to calendar, Events.TITLE to title, Events.ALL_DAY to 0,
                Events.DTSTART to day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                Events.DTEND to day.atTime(hour + 1, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ) + extra
        )
        add("Svátek", 4, 6)
        add("Cizí", 3, 7, mapOf(Events.ORGANIZER to "sef@firma.cz"))
        val invited = add("Pozvánka", 3, 9, mapOf(Events.ORGANIZER to "sef@firma.cz"))
        FakeCalendarProvider.attendees += mutableMapOf(
            Attendees.EVENT_ID to invited, Attendees.ATTENDEE_EMAIL to "ja@firma.cz", Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED,
        )
        add("Sprint testProjekt", 3, 8, mapOf(Events.ORGANIZER to "sef@firma.cz"))
        add("Oběd", 1, 12)

        val titles = repository.eventsByDay(day, day, emptySet(), ranking = ranking).getValue(day).map { it.title }
        assertEquals(listOf("Oběd", "Sprint testProjekt", "Pozvánka", "Cizí", "Svátek"), titles)
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
    fun importOfSameEventFromTwoFilesAddsItOnce() {
        val ics = "BEGIN:VEVENT\r\nSUMMARY:Oběd\r\nDTSTART:${day.toString().replace("-", "")}T120000\r\nEND:VEVENT"

        assertEquals(1, repository.import(parseIcs(ics) + parseIcs(ics), 1))
        assertEquals(1, FakeCalendarProvider.events.size)
    }

    @Test
    fun importAttachesMovedOccurrenceToItsSeries() {
        val text = listOf(
            "BEGIN:VEVENT", "UID:joga", "RECURRENCE-ID:20261116T170000Z", "SUMMARY:Jóga",
            "DTSTART:20261117T170000Z", "DTEND:20261117T180000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:joga", "SUMMARY:Jóga", "DTSTART:20261102T170000Z", "DTEND:20261102T180000Z",
            "RRULE:FREQ=WEEKLY;COUNT=4", "EXDATE:20261109T170000Z", "END:VEVENT",
        ).joinToString("\r\n")

        assertEquals(2, repository.import(parseIcs(text), 1))

        val series = FakeCalendarProvider.events.first { it[Events.RRULE] != null }
        assertEquals("20261109T170000Z", series[Events.EXDATE])
        val moved = FakeCalendarProvider.events.first { it[Events.RRULE] == null }
        assertEquals(series[Events._ID], moved[Events.ORIGINAL_ID])
        assertEquals(1794848400000L, moved[Events.ORIGINAL_INSTANCE_TIME])
    }

    @Test
    fun importKeepsChangeOfFirstOccurrence() {
        val text = listOf(
            "BEGIN:VEVENT", "UID:joga", "SUMMARY:Jóga", "DTSTART:20261102T170000Z", "DTEND:20261102T180000Z",
            "RRULE:FREQ=WEEKLY;COUNT=4", "END:VEVENT",
            "BEGIN:VEVENT", "UID:joga", "RECURRENCE-ID:20261102T170000Z", "SUMMARY:Jóga", "LOCATION:Jiný sál",
            "DTSTART:20261102T170000Z", "DTEND:20261102T180000Z", "END:VEVENT",
        ).joinToString("\r\n")

        assertEquals(2, repository.import(parseIcs(text), 1))
        assertEquals("Jiný sál", FakeCalendarProvider.events.single { it[Events.ORIGINAL_ID] != null }[Events.EVENT_LOCATION])
        // Importing the same file again adds nothing
        assertEquals(0, repository.import(parseIcs(text), 1))
    }

    @Test
    fun editingSeriesKeepsItsOwnTimeZone() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))
        try {
            val newYork = ZoneId.of("America/New_York")
            val start = java.time.LocalDateTime.of(2026, 6, 1, 10, 0).atZone(newYork).toInstant().toEpochMilli()
            val id = FakeCalendarProvider.addEvent(
                mapOf(
                    Events.CALENDAR_ID to 1L, Events.TITLE to "Call", Events.ALL_DAY to 0, Events.RRULE to "FREQ=WEEKLY",
                    Events.DTSTART to start, Events.DURATION to "P3600S", Events.EVENT_TIMEZONE to "America/New_York",
                )
            )
            val occurrence = java.time.LocalDateTime.of(2026, 11, 9, 10, 0).atZone(newYork).toInstant().toEpochMilli()

            val opened = repository.event(id, occurrence)!!
            repository.saveEvent(opened.copy(title = "Call s klientem"))

            val row = FakeCalendarProvider.event(id)
            assertEquals("America/New_York", row[Events.EVENT_TIMEZONE])
            assertEquals(start, row[Events.DTSTART])
            assertEquals("Call s klientem", row[Events.TITLE])
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun importedSeriesKeepsTimeZoneOfFile() {
        val text = listOf(
            "BEGIN:VEVENT", "UID:abc", "SUMMARY:US sync", "DTSTART;TZID=America/New_York:20260910T100000",
            "DTEND;TZID=America/New_York:20260910T110000", "RRULE:FREQ=WEEKLY", "END:VEVENT",
        ).joinToString("\r\n")

        repository.import(parseIcs(text), 1)

        val row = FakeCalendarProvider.events.single()
        assertEquals("America/New_York", row[Events.EVENT_TIMEZONE])
        val start = java.time.LocalDateTime.of(2026, 9, 10, 10, 0).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals(start, row[Events.DTSTART])
    }

    @Test
    fun movingSeriesToAnotherDayMovesRuleAndHistory() {
        val tuesday = java.time.LocalDate.of(2026, 11, 3)
        val zone = ZoneId.systemDefault()
        fun at(day: java.time.LocalDate) = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val id = FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to 1L, Events.TITLE to "Kurz", Events.ALL_DAY to 0, Events.DTSTART to at(tuesday),
                Events.DURATION to "P3600S", Events.RRULE to "FREQ=WEEKLY;WKST=MO;BYDAY=TU", Events.EVENT_TIMEZONE to zone.id,
                Events.EXDATE to java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
                    .format(java.time.Instant.ofEpochMilli(at(tuesday.plusDays(14)))),
            )
        )
        repository.deleteOccurrence(id, at(tuesday.plusDays(7)))

        val opened = repository.event(id, at(tuesday.plusDays(21)))!!
        repository.saveEvent(opened.copy(start = opened.start.plusDays(1), end = opened.end.plusDays(1)))

        val series = FakeCalendarProvider.event(id)
        assertEquals("FREQ=WEEKLY;WKST=MO;BYDAY=WE", series[Events.RRULE])
        assertEquals(at(tuesday.plusDays(1)), series[Events.DTSTART])
        val cancelled = FakeCalendarProvider.events.single { it[Events.ORIGINAL_ID] == id }
        assertEquals(at(tuesday.plusDays(8)), cancelled[Events.ORIGINAL_INSTANCE_TIME])
        val skipped = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(at(tuesday.plusDays(15))))
        assertEquals(skipped, series[Events.EXDATE])
    }

    @Test
    fun savingUnchangedTimeInRepeatedDaylightSavingHourKeepsIt() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))
        try {
            // 02:30 for the second time, after clocks went back
            val begin = java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
            val id = FakeCalendarProvider.addEvent(
                mapOf(Events.CALENDAR_ID to 1L, Events.TITLE to "Noční", Events.ALL_DAY to 0, Events.DTSTART to begin, Events.DTEND to begin + 3600_000L),
            )

            repository.saveEvent(repository.event(id)!!.copy(title = "Noční služba"))

            assertEquals(begin, FakeCalendarProvider.event(id)[Events.DTSTART])
            assertEquals(begin + 3600_000L, FakeCalendarProvider.event(id)[Events.DTEND])
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun switchingSeriesBetweenAllDayAndTimedKeepsSkippedOccurrences() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))
        try {
            val prague = ZoneId.of("Europe/Prague")
            val monday = java.time.LocalDate.of(2026, 3, 2)
            val skipped = monday.plusWeeks(5)
            val id = repository.saveEvent(
                EventDraft.new(1, monday).copy(rule = "FREQ=WEEKLY", title = "Kurz"),
            )
            repository.deleteOccurrence(id, skipped.atTime(9, 0).atZone(prague).toInstant().toEpochMilli())

            // Timed to all-day: the skipped occurrence becomes midnight UTC of its day
            val opened = repository.event(id, monday.plusWeeks(1).atTime(9, 0).atZone(prague).toInstant().toEpochMilli())!!
            repository.saveEvent(opened.copy(allDay = true))
            val cancelled = { FakeCalendarProvider.events.single { it[Events.ORIGINAL_ID] == id }[Events.ORIGINAL_INSTANCE_TIME] }
            assertEquals(skipped.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), cancelled())

            // And back to 9:00 on the clock, also after the clocks moved forward
            val reopened = repository.event(id, monday.plusWeeks(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli())!!
            val day = monday.plusWeeks(1)
            repository.saveEvent(reopened.copy(allDay = false, start = day.atTime(9, 0), end = day.atTime(10, 0)))
            assertEquals(skipped.atTime(9, 0).atZone(prague).toInstant().toEpochMilli(), cancelled())
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun allDaySeriesBecomingTimedKeepsSkippedDate() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))
        try {
            val text = listOf(
                "BEGIN:VEVENT", "UID:a", "SUMMARY:Úklid", "DTSTART;VALUE=DATE:20261102", "DTEND;VALUE=DATE:20261103",
                "RRULE:FREQ=WEEKLY", "EXDATE;VALUE=DATE:20261116", "END:VEVENT",
            ).joinToString("\r\n")
            repository.import(parseIcs(text), 1)
            val id = FakeCalendarProvider.events.single()[Events._ID] as Long

            val ninth = java.time.LocalDate.of(2026, 11, 9)
            val opened = repository.event(id, ninth.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli())!!
            repository.saveEvent(opened.copy(allDay = false, start = ninth.atTime(10, 0), end = ninth.atTime(11, 0)))

            assertEquals("20261116T090000Z", FakeCalendarProvider.event(id)[Events.EXDATE])
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun renamingNightSeriesFromSpringForwardDayKeepsItsTime() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Prague"))
        try {
            val prague = ZoneId.of("Europe/Prague")
            val start = java.time.LocalDateTime.of(2026, 3, 20, 2, 30).atZone(prague).toInstant().toEpochMilli()
            val id = FakeCalendarProvider.addEvent(
                mapOf(
                    Events.CALENDAR_ID to 1L, Events.TITLE to "Záloha", Events.ALL_DAY to 0, Events.RRULE to "FREQ=DAILY",
                    Events.DTSTART to start, Events.DURATION to "P1800S", Events.EVENT_TIMEZONE to "Europe/Prague",
                )
            )
            // 02:30 does not exist that night, the provider shows it at 03:30
            val occurrence = java.time.Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()

            repository.saveEvent(repository.event(id, occurrence)!!.copy(title = "Záloha serveru"))

            assertEquals(start, FakeCalendarProvider.event(id)[Events.DTSTART])
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun stoppingRepetitionRemovesChangedOccurrences() {
        val id = repository.saveEvent(EventDraft.new(1, day).copy(rule = "FREQ=WEEKLY", title = "Porada"))
        val series = FakeCalendarProvider.event(id)[Events.DTSTART] as Long
        val second = repository.event(id, series + 7 * 24 * 3600_000L)!!
        repository.saveOccurrence(second.copy(start = second.start.plusDays(3), end = second.end.plusDays(3)))

        repository.saveEvent(repository.event(id, series)!!.copy(rule = null))

        assertEquals(listOf(id), FakeCalendarProvider.events.map { it[Events._ID] })
    }

    @Test
    fun turningSeriesIntoSingleEventKeepsShownOccurrence() {
        val seriesId = repository.saveEvent(EventDraft.new(1, day).copy(rule = "FREQ=WEEKLY"))
        val occurrence = (FakeCalendarProvider.event(seriesId)[Events.DTSTART] as Long) + 14 * 24 * 3600_000L

        repository.saveEvent(repository.event(seriesId, occurrence)!!.copy(rule = null))

        val row = FakeCalendarProvider.event(seriesId)
        assertEquals(occurrence, row[Events.DTSTART])
        assertEquals(occurrence + 3600_000L, row[Events.DTEND])
        assertNull(row[Events.RRULE])
    }

    @Test
    fun spreadsMultiDayEventsAcrossDays() {
        repository.saveEvent(EventDraft.new(1, day).copy(title = "Hory", allDay = true, end = day.plusDays(2).atStartOfDay()))

        val events = repository.eventsByDay(day.minusDays(1), day.plusDays(3), hidden = emptySet())
        assertEquals(listOf(day, day.plusDays(1), day.plusDays(2)), events.keys.sorted())
        assertEquals(true, events.getValue(day).single().multiDay)
    }
}
