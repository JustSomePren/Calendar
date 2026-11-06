package cz.calendar

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
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
        FakeCalendarProvider.addCalendar(2, "Práce", account = "ja@firma.cz")
        FakeCalendarProvider.addCalendar(3, "Svátky")
        settings = Settings(app)
        settings.setCategory(2, Category.WORK)
        settings.setCategory(3, Category.QUIET)
        settings.workEmails = listOf("ja@firma.cz")
        settings.workKeywords = listOf("testProjekt")
        settings.notifierStarted = true
    }

    // Starts in 20 minutes, so its "30 min before" reminder is due now
    // Work events are someone else's unless said otherwise
    private fun soon(
        calendar: Long,
        title: String,
        description: String = "",
        organizer: String = if (calendar == 2L) "sef@firma.cz" else "",
    ) = FakeCalendarProvider.addEvent(
        mapOf(
            Events.CALENDAR_ID to calendar,
            Events.TITLE to title,
            Events.DESCRIPTION to description,
            Events.ORGANIZER to organizer,
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
        soon(2, "Moje review", organizer = "ja@firma.cz")
        soon(2, "Cizí schůzka")
        soon(3, "Svátek")

        EventNotifier.run(app, now)

        assertEquals(listOf("Moje review", "Porada", "Sprint", "Zubař"), shownTitles())
    }

    @Test
    fun watchOverridesCategory() {
        settings.setWatch(soon(2, "Cizí standup"), Watch.ALWAYS)
        settings.setWatch(soon(3, "Svátek, co chci"), Watch.ALWAYS)
        settings.setWatch(soon(1, "Týdenní úklid"), Watch.NEVER)

        EventNotifier.run(app, now)

        assertEquals(listOf("Cizí standup", "Svátek, co chci"), shownTitles())
    }

    @Test
    fun changedOccurrenceFollowsMutedSeries() {
        val series = soon(1, "Úklid")
        FakeCalendarProvider.events.single { it[Events._ID] == series }[Events.DTSTART] = now - 7 * 24 * 3600_000L
        FakeCalendarProvider.events.single { it[Events._ID] == series }[Events.DTEND] = now - 7 * 24 * 3600_000L + 3600_000L
        val moved = soon(1, "Úklid jindy")
        FakeCalendarProvider.event(moved)[Events.ORIGINAL_ID] = series
        settings.setWatch(series, Watch.NEVER)

        EventNotifier.run(app, now)

        assertEquals(emptyList<String>(), shownTitles())
    }

    @Test
    fun longVacationRemindsOnlyOnce() {
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.now().plusDays(3)
        FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to 1L,
                Events.TITLE to "Dovolená",
                Events.ALL_DAY to 1,
                Events.DTSTART to start.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                Events.DTEND to start.plusDays(9).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
            )
        )
        fun at(day: java.time.LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

        EventNotifier.run(app, at(start.minusDays(3), 18, 1))
        EventNotifier.run(app, at(start.minusDays(1), 18, 1))
        // Over a week later, while the vacation still runs
        EventNotifier.run(app, at(start.plusDays(6), 10, 0))
        EventNotifier.run(app, at(start.plusDays(6), 10, 1))

        val notifications = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals(1, notifications.allNotifications.size)
        assertEquals(2, settings.firedReminders.size)
    }

    @Test
    fun colleaguesSharedCalendarIsNotMine() {
        FakeCalendarProvider.addCalendar(4, "Petr", account = "ja@firma.cz", owner = "petr@firma.cz")
        settings.setCategory(4, Category.WORK)
        soon(4, "Petrův oběd s klientem", organizer = "petr@firma.cz")
        soon(4, "Moje schůzka v Petrově kalendáři", organizer = "ja@firma.cz")

        EventNotifier.run(app, now)

        assertEquals(listOf("Moje schůzka v Petrově kalendáři"), shownTitles())
    }

    @Test
    fun muteButtonStopsFurtherReminders() {
        val weekly = soon(1, "Týdenní úklid")
        val mute = Intent(app, ReminderReceiver::class.java)
            .setAction(ACTION_MUTE)
            .putExtra(EXTRA_EVENT_ID, weekly)

        ReminderReceiver().onReceive(app, mute)

        assertEquals(Watch.NEVER, settings.watch(weekly))
    }

    @Test
    fun nothingRepeats() {
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
