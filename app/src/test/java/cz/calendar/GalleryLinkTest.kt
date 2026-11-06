package cz.calendar

import android.app.Application
import android.net.Uri
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class GalleryLinkTest {

    private lateinit var app: Application
    private lateinit var settings: Settings
    private val day = LocalDate.of(2026, 8, 10)

    @Before
    fun setUp() {
        app = setUpFakeCalendar()
        settings = Settings(app)
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        FakeCalendarProvider.addCalendar(1, "Osobní")
        FakeCalendarProvider.addCalendar(2, "Práce")
        settings.setCategory(2, Category.WORK)
    }

    private fun addTrip(calendarId: Long = 1): Long = FakeCalendarProvider.addEvent(
        mapOf(
            Events.CALENDAR_ID to calendarId,
            Events.TITLE to "Chorvatsko",
            Events.DTSTART to day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            Events.DTEND to day.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            Events.ALL_DAY to 1,
        ),
    )

    @Test
    fun vacationIsStoredPerEvent() {
        assertFalse(settings.hasVacations)
        settings.setVacation(5, Vacation(album = "Moře", account = "ja@gmail.com", autoSend = true))

        assertEquals(Vacation("Moře", "ja@gmail.com", true), settings.vacation(5))
        assertNull(settings.vacation(6))
        assertTrue(settings.hasVacations)

        settings.setVacation(5, null)
        assertNull(settings.vacation(5))
        assertFalse(settings.hasVacations)
    }

    @Test
    fun galleryGetsPersonalEventsWithTheirVacation() {
        val trip = addTrip()
        addTrip(calendarId = 2)
        settings.setVacation(trip, Vacation(album = "", account = "ja@gmail.com", autoSend = true))

        linkedEvents(app, day.minusDays(1), day.plusDays(10)).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(trip, c.getLong(c.getColumnIndexOrThrow("id")))
            assertEquals(day.toEpochDay(), c.getLong(c.getColumnIndexOrThrow("first_day")))
            assertEquals(day.plusDays(6).toEpochDay(), c.getLong(c.getColumnIndexOrThrow("last_day")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("vacation")))
            // An empty album means "named like the event"
            assertEquals("Chorvatsko", c.getString(c.getColumnIndexOrThrow("album")))
            assertEquals("ja@gmail.com", c.getString(c.getColumnIndexOrThrow("account")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("auto_send")))
        }
    }

    @Test
    fun timedEventTakesPhotosFromShortlyBeforeToAfter() {
        val start = day.atTime(18, 0)
        val draft = EventDraft(calendarId = 1, start = start, end = start.plusHours(4))
        val zone = ZoneId.systemDefault()

        val (from, to) = GalleryLink.window(draft)
        assertEquals(start.minusMinutes(15).atZone(zone).toInstant().toEpochMilli(), from)
        assertEquals(start.plusHours(4).plusMinutes(30).atZone(zone).toInstant().toEpochMilli(), to)
    }

    @Test
    fun vacationTakesWholeDays() {
        val draft = EventDraft(calendarId = 1, start = day.atTime(15, 0), end = day.plusDays(2).atTime(20, 0), vacation = Vacation(""))
        val zone = ZoneId.systemDefault()

        val (from, to) = GalleryLink.window(draft)
        assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(day.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli(), to)
    }

    @Test
    fun otherAppsGetNothing() {
        addTrip()
        val provider = Robolectric.buildContentProvider(LinkProvider::class.java).create().get()
        assertNull(provider.query(Uri.parse("content://cz.calendar.link/events?from=0&to=99999"), null, null, null, null))
    }
}
