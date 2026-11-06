package cz.calendar

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
class NotifierTest {

    private lateinit var app: Application
    private lateinit var settings: Settings
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        app = setUpFakeCalendar()
        FakeCalendarProvider.addCalendar(1, "Osobní")
        FakeCalendarProvider.addCalendar(2, "Práce")
        FakeCalendarProvider.addCalendar(3, "Svátky")
        settings = Settings(app)
        settings.setCategory(2, Category.WORK)
        settings.setCategory(3, Category.QUIET)
        settings.workEmails = listOf("ja@firma.cz")
        settings.workKeywords = listOf("testProjekt")
        settings.notifierStarted = true
    }

    // Starts in 20 minutes, so its "30 min before" reminder is due now
    private fun soon(calendar: Long, title: String, description: String = "") = FakeCalendarProvider.addEvent(
        mapOf(
            Events.CALENDAR_ID to calendar,
            Events.TITLE to title,
            Events.DESCRIPTION to description,
            Events.DTSTART to now + 20 * 60_000,
            Events.DTEND to now + 80 * 60_000,
            Events.ALL_DAY to 0,
        )
    )

    private fun shownTitles() = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        .allNotifications.mapNotNull { it.extras.getString("android.title") }.sorted()

    @Test
    fun notifiesByCategoryAndWorkFilter() {
        soon(1, "Zubař")
        soon(2, "Sprint", description = "Plánování testProjekt")
        val invited = soon(2, "Porada")
        FakeCalendarProvider.attendees += mutableMapOf(
            Attendees.EVENT_ID to invited,
            Attendees.ATTENDEE_EMAIL to "ja@firma.cz",
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED,
        )
        soon(2, "Cizí schůzka")
        soon(3, "Svátek")

        EventNotifier.run(app, now)

        assertEquals(listOf("Porada", "Sprint", "Zubař"), shownTitles())
    }

    @Test
    fun priorityNoneIsSilentAndNothingRepeats() {
        val quiet = soon(1, "Nevadí")
        settings.setPriority(quiet, Priority.NONE)
        soon(1, "Zubař")

        EventNotifier.run(app, now)
        EventNotifier.run(app, now + 60_000)

        assertEquals(listOf("Zubař"), shownTitles())
    }

    @Test
    fun firstRunDoesNotFloodOldReminders() {
        settings.notifierStarted = false
        soon(1, "Zubař")

        EventNotifier.run(app, now)

        assertEquals(emptyList<String>(), shownTitles())
    }

    @Test
    fun schedulesAlarmForNextReminder() {
        FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to 1L,
                Events.TITLE to "Později",
                Events.DTSTART to now + 5 * 3600_000L,
                Events.DTEND to now + 6 * 3600_000L,
                Events.ALL_DAY to 0,
            )
        )

        EventNotifier.run(app, now)

        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        // Reminder 30 min before wakes the phone, the midnight refresh does not
        assertEquals(now + (5 * 60 - 30) * 60_000L, alarms.single { it.getType() == AlarmManager.RTC_WAKEUP }.getTriggerAtMs())
        val midnight = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(midnight, alarms.single { it.getType() == AlarmManager.RTC }.getTriggerAtMs())
    }
}
