package cz.calendar

import android.app.Application
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StableIdsTest {

    private lateinit var app: Application
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        app = setUpFakeCalendar()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = Settings(app)
    }

    private fun googleCalendar(id: Long, name: String) {
        FakeCalendarProvider.addCalendar(id, name)
        FakeCalendarProvider.calendars.last()[Calendars.ACCOUNT_TYPE] = "com.google"
        FakeCalendarProvider.calendars.last()[Calendars.NAME] = "$name@group.calendar.google.com"
    }

    private fun event(calendarId: Long, title: String, syncId: String?) = FakeCalendarProvider.addEvent(
        mapOf(
            Events.CALENDAR_ID to calendarId,
            Events.TITLE to title,
            Events.DTSTART to 1_000_000L,
            Events.DTEND to 2_000_000L,
            Events._SYNC_ID to syncId,
        ),
    )

    @Test
    fun settingsFollowTheirEventsToANewPhone() {
        googleCalendar(1, "rodina")
        googleCalendar(2, "prace")
        val trip = event(1, "Chorvatsko", "google-trip")
        val local = event(1, "Zubař", null)
        settings.setCategory(2, Category.WORK)
        settings.hiddenCalendars = setOf(2)
        settings.setPriority(trip, Priority.HIGH)
        settings.setVacation(trip, Vacation("Moře"))
        settings.setWatch(local, Watch.NEVER)
        repairIds(app)
        val backup = settings.exportBackup()

        // New phone: same Google calendars and events, numbered differently
        FakeCalendarProvider.reset()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        googleCalendar(7, "prace")
        googleCalendar(8, "rodina")
        val stranger = event(8, "Cizí událost", "google-other")
        val newTrip = event(8, "Chorvatsko", "google-trip")
        val newLocal = event(8, "Zubař", null)
        assertEquals(trip, stranger)
        settings.restoreBackup(backup)
        repairIds(app)

        assertEquals(Category.WORK, settings.category(7))
        assertEquals(Category.PERSONAL, settings.category(2))
        assertEquals(setOf(7L), settings.hiddenCalendars)
        assertEquals(Priority.HIGH, settings.priority(newTrip))
        assertEquals(Vacation("Moře"), settings.vacation(newTrip))
        assertEquals(Watch.NEVER, settings.watch(newLocal))
        assertEquals(Priority.NORMAL, settings.priority(stranger))
        assertNull(settings.vacation(stranger))
    }

    @Test
    fun unsyncedPhoneWaitsInsteadOfGuessing() {
        googleCalendar(1, "rodina")
        val trip = event(1, "Chorvatsko", "google-trip")
        settings.setPriority(trip, Priority.HIGH)
        repairIds(app)

        // The new phone has not synced the calendar yet
        val backup = settings.exportBackup()
        FakeCalendarProvider.reset()
        settings.restoreBackup(backup)
        repairIds(app)
        assertEquals(Priority.HIGH, settings.priority(trip))

        googleCalendar(5, "rodina")
        val synced = event(5, "Chorvatsko", "google-trip")
        repairIds(app)
        assertEquals(Priority.HIGH, settings.priority(synced))
    }
}
