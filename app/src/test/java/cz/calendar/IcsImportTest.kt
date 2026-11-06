package cz.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class IcsImportTest {

    private val zone = ZoneId.of("Europe/Prague")

    private val samsungExport = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Samsung Electronics//Calendar//EN
        BEGIN:VEVENT
        UID:1
        SUMMARY:Zubař\, kontrola
        LOCATION:Vinohradská 12
        DESCRIPTION:Vzít kartičku\npojišťovny
        DTSTART;TZID=Europe/Prague:20261110T090000
        DTEND;TZID=Europe/Prague:20261110T100000
        BEGIN:VALARM
        TRIGGER:-PT30M
        DESCRIPTION:Ignore me
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:2
        SUMMARY:Dovolená
        DTSTART;VALUE=DATE:20261120
        DTEND;VALUE=DATE:20261124
        END:VEVENT
        BEGIN:VEVENT
        UID:3
        SUMMARY:Fotbal
        DTSTART:20261102T170000Z
        DURATION:PT2H
        RRULE:FREQ=WEEKLY;BYDAY=MO
        END:VEVENT
        BEGIN:VEVENT
        UID:3
        RECURRENCE-ID:20261109T170000Z
        SUMMARY:Fotbal přesunut
        DTSTART:20261110T170000Z
        END:VEVENT
        BEGIN:VEVENT
        UID:4
        SUMMARY:Zrušeno
        STATUS:CANCELLED
        DTSTART:20261111T170000Z
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun readsSamsungExport() {
        val events = parseIcs(samsungExport, zone)

        assertEquals(listOf("Zubař, kontrola", "Dovolená", "Fotbal"), events.map { it.title })

        val dentist = events[0]
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), dentist.start)
        assertEquals(LocalDateTime.of(2026, 11, 10, 10, 0), dentist.end)
        assertEquals("Vzít kartičku\npojišťovny", dentist.description)
        assertEquals("Vinohradská 12", dentist.location)
        assertNull(dentist.rule)

        val holiday = events[1]
        assertTrue(holiday.allDay)
        assertEquals(LocalDate.of(2026, 11, 20), holiday.start.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 23), holiday.end.toLocalDate())

        val football = events[2]
        assertEquals(LocalDateTime.of(2026, 11, 2, 18, 0), football.start)
        assertEquals(LocalDateTime.of(2026, 11, 2, 20, 0), football.end)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", football.rule)
    }

    @Test
    fun unfoldsLongLines() {
        val text = "BEGIN:VEVENT\r\nSUMMARY:Dlouhý\r\n  název\r\nDTSTART:20261110T090000\r\nEND:VEVENT"
        assertEquals("Dlouhý název", parseIcs(text, zone).single().title)
    }

    @Test
    fun ignoresBrokenEvents() {
        assertTrue(parseIcs("BEGIN:VEVENT\r\nSUMMARY:Bez data\r\nEND:VEVENT", zone).isEmpty())
        assertTrue(parseIcs("nesmysl", zone).isEmpty())
    }
}
