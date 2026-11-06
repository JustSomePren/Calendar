package cz.calendar

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.provider.CalendarContract.Events
import android.widget.TextView
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

// Whole user flows, clicked through like on the phone
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class UserFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var app: Application
    private lateinit var settings: Settings
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        app = setUpFakeCalendar()
        FakeCalendarProvider.addCalendar(1, "Rodina")
        FakeCalendarProvider.addCalendar(2, "Práce", owner = "ja@firma.cz")
        settings = Settings(app)
    }

    private fun at(hour: Int, day: LocalDate = today) = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun addEvent(title: String, extra: Map<String, Any?> = emptyMap()) = FakeCalendarProvider.addEvent(
        mapOf(
            Events.CALENDAR_ID to 1L,
            Events.TITLE to title,
            Events.DTSTART to at(9),
            Events.DTEND to at(10),
            Events.ALL_DAY to 0,
        ) + extra
    )

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw ComposeTimeoutException("Condition not met")
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun editor(eventId: Long? = null, instanceBegin: Long? = null): Intent =
        Intent(app, EventEditActivity::class.java).apply {
            eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
            instanceBegin?.let { putExtra(EXTRA_INSTANCE_BEGIN, it) }
            putExtra(EXTRA_DAY, today.toString())
        }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun choose(current: String, option: String, index: Int = 0) {
        compose.onAllNodes(hasClickAction() and hasText(current))[index].performClick()
        compose.onNode(hasClickAction() and hasText(option)).performClick()
    }

    @Test
    fun createEventWithPriorityAndWatch() {
        ActivityScenario.launch<EventEditActivity>(editor()).use {
            waitForText("Nová událost")
            field("Název").performTextInput("Kadeřník")
            field("Místo").performTextInput("Brno")
            choose("Normální", "Vysoká")
            choose("Podle kalendáře", "Vždy (sledovat)")
            compose.onNodeWithText("Uložit").performClick()
            waitFor { FakeCalendarProvider.events.isNotEmpty() }
        }

        val saved = FakeCalendarProvider.events.single()
        assertEquals("Kadeřník", saved[Events.TITLE])
        assertEquals("Brno", saved[Events.EVENT_LOCATION])
        assertEquals(1L, saved[Events.CALENDAR_ID])
        assertEquals(at(9), saved[Events.DTSTART])
        val id = saved[Events._ID] as Long
        waitFor { settings.priority(id) == Priority.HIGH }
        assertEquals(Watch.ALWAYS, settings.watch(id))
        assertEquals(1L, settings.lastCalendar)
    }

    @Test
    fun allDayEventIsSavedInUtc() {
        ActivityScenario.launch<EventEditActivity>(editor()).use {
            waitForText("Nová událost")
            field("Název").performTextInput("Narozeniny")
            compose.onNode(hasText("Celý den")).assertExists()
            compose.onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
            compose.onNodeWithText("Uložit").performClick()
            waitFor { FakeCalendarProvider.events.isNotEmpty() }
        }

        val saved = FakeCalendarProvider.events.single()
        assertEquals(1, saved[Events.ALL_DAY])
        assertEquals("UTC", saved[Events.EVENT_TIMEZONE])
        assertEquals(today.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), saved[Events.DTSTART])
    }

    @Test
    fun muteWeeklyPersonalEvent() {
        val weekly = addEvent("Úklid", mapOf(Events.RRULE to "FREQ=WEEKLY", Events.DTEND to null, Events.DURATION to "P3600S"))

        ActivityScenario.launch<EventEditActivity>(editor(weekly, at(9))).use {
            waitForText("Osobní kalendář: upozorní.")
            choose("Podle kalendáře", "Nikdy")
            compose.onNodeWithText("Platí pro všechna opakování.").assertExists()
            compose.onNodeWithText("Uložit").performClick()
            waitForText("Jen tento výskyt")
            compose.onNodeWithText("Celou sérii").performClick()
            waitFor { settings.watch(weekly) == Watch.NEVER }
        }
    }

    @Test
    fun changeOneOccurrenceOnly() {
        val daily = addEvent("Standup", mapOf(Events.RRULE to "FREQ=DAILY", Events.DTEND to null, Events.DURATION to "P900S"))
        val tomorrow = at(9) + 24 * 3600_000L

        ActivityScenario.launch<EventEditActivity>(editor(daily, tomorrow)).use {
            waitForText("Standup")
            field("Název").performTextReplacement("Standup venku")
            compose.onNodeWithText("Uložit").performClick()
            waitForText("Jen tento výskyt")
            compose.onNodeWithText("Jen tento výskyt").performClick()
            waitFor { FakeCalendarProvider.events.size == 2 }
        }

        val exception = FakeCalendarProvider.events.first { it[Events._ID] != daily }
        assertEquals(tomorrow, exception[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals("Standup venku", exception[Events.TITLE])
        assertEquals("Standup", FakeCalendarProvider.event(daily)[Events.TITLE])
    }

    @Test
    fun deleteEvent() {
        val id = addEvent("Zrušená schůzka")

        ActivityScenario.launch<EventEditActivity>(editor(id)).use {
            waitForText("Zrušená schůzka")
            compose.onNodeWithContentDescription("Smazat").performClick()
            waitForText("Smazat událost?")
            compose.onNode(hasClickAction() and hasText("Smazat")).performClick()
            waitFor { FakeCalendarProvider.events.isEmpty() }
        }
    }

    @Test
    fun workEventShowsWhyItIsNotReminded() {
        settings.setCategory(2, Category.WORK)
        val id = addEvent("Cizí porada", mapOf(Events.CALENDAR_ID to 2L, Events.ORGANIZER to "sef@firma.cz"))

        ActivityScenario.launch<EventEditActivity>(editor(id)).use {
            waitForText("Pracovní kalendář: neupozorní. Pro sledování zvol „Vždy“.")
        }
    }

    @Test
    fun readOnlyCalendarCanStillBeFollowed() {
        FakeCalendarProvider.addCalendar(3, "Svátky", writable = false)
        val id = addEvent("Den boje za svobodu", mapOf(Events.CALENDAR_ID to 3L))

        ActivityScenario.launch<EventEditActivity>(editor(id)).use {
            waitForText("Tento kalendář je jen pro čtení.")
            compose.onNodeWithContentDescription("Smazat").assertDoesNotExist()
            choose("Podle kalendáře", "Vždy (sledovat)")
            compose.onNodeWithText("Uložit").performClick()
            waitFor { settings.watch(id) == Watch.ALWAYS }
        }
        assertEquals("Den boje za svobodu", FakeCalendarProvider.event(id)[Events.TITLE])
    }

    @Test
    fun settingsChangeCategoryAndWorkFilter() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            waitForText("Práce")
            // Second "Osobní" button belongs to the second calendar
            choose("Osobní", "Pracovní", index = 1)
            field("E-maily účastníků, oddělené čárkou").performTextInput("ja@firma.cz, kolega@firma.cz")
            field("Slova v události, např. testProjekt").performTextInput("testProjekt")
        }

        assertEquals(Category.PERSONAL, settings.category(1))
        assertEquals(Category.WORK, settings.category(2))
        assertEquals(listOf("ja@firma.cz", "kolega@firma.cz"), settings.workEmails)
        assertEquals(listOf("testProjekt"), settings.workKeywords)
    }

    @Test
    fun hidingCalendarRemovesItsEvents() {
        addEvent("Tajná věc", mapOf(Events.CALENDAR_ID to 2L))
        settings.hiddenCalendars = setOf(2L)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(monthTitle(java.time.YearMonth.now()))
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("Tajná věc").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun tapEventInDayPanelOpensEditor() {
        val id = addEvent("Zubař")
        val intent = Intent(app, MainActivity::class.java).putExtra(EXTRA_DAY, today.toString())

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitForText("09:00 – 10:00")
            // Robolectric does not pass touches into the bottom sheet window, so click via accessibility
            compose.onNodeWithText("09:00 – 10:00").performSemanticsAction(SemanticsActions.OnClick)
            scenario.onActivity { activity ->
                val started = Shadows.shadowOf(activity).nextStartedActivity
                assertEquals(EventEditActivity::class.java.name, started.component?.className)
                assertEquals(id, started.getLongExtra(EXTRA_EVENT_ID, -1))
            }

            compose.onNode(hasClickAction() and hasText("Přidat událost")).performSemanticsAction(SemanticsActions.OnClick)
            scenario.onActivity { activity ->
                val started = Shadows.shadowOf(activity).nextStartedActivity
                assertEquals(EventEditActivity::class.java.name, started.component?.className)
                assertEquals(today.toString(), started.getStringExtra(EXTRA_DAY))
            }
        }
    }

    @Test
    fun widgetShowsMonthAndEvents() {
        val trip = FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to 1L,
                Events.TITLE to "Hory",
                Events.ALL_DAY to 1,
                Events.DTSTART to today.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                Events.DTEND to today.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
            )
        )
        settings.setPriority(trip, Priority.HIGH)

        val views = runBlocking { MonthWidget().compose(app, size = DpSize(360.dp, 420.dp)) }
        val root = views.apply(app, android.widget.FrameLayout(app))
        val texts = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view is TextView) texts += view.text.toString()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
        }
        collect(root)

        assertTrue(monthTitle(java.time.YearMonth.now()) in texts)
        assertTrue("! Hory" in texts)
        assertTrue("Po" in texts && "Ne" in texts)
    }

    @Test
    fun widgetAsksForPermissionWithoutAccess() {
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR)

        val views = runBlocking { MonthWidget().compose(app, size = DpSize(360.dp, 420.dp)) }
        val root = views.apply(app, android.widget.FrameLayout(app))
        val texts = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view is TextView) texts += view.text.toString()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
        }
        collect(root)

        assertTrue("Otevři aplikaci a povol přístup ke kalendářům." in texts)
    }

    @Test
    fun withoutAccessAppAsksForIt() {
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Aplikace potřebuje přístup ke kalendářům v telefonu.")
            compose.onNodeWithText("Otevřít nastavení aplikace").performClick()
            scenario.onActivity { activity ->
                val started = Shadows.shadowOf(activity).nextStartedActivity
                assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
            }
        }
    }

    @Test
    fun settingsOpensGoogleCalendarNotifications() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            waitForText("Upozornění Google Kalendáře")
            compose.onNodeWithText("Upozornění Google Kalendáře").performScrollTo().performClick()
            scenario.onActivity { activity ->
                val started = Shadows.shadowOf(activity).nextStartedActivity
                assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
                assertEquals("com.google.android.calendar", started.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
            }
        }
    }

    @Test
    fun afterRestartRemindersAreScheduledAgain() {
        settings.notifierStarted = true
        addEvent("Odpolední schůzka", mapOf(Events.DTSTART to System.currentTimeMillis() + 3 * 3600_000L, Events.DTEND to System.currentTimeMillis() + 4 * 3600_000L))

        ReminderReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        waitFor { alarms.scheduledAlarms.any { it.getType() == AlarmManager.RTC_WAKEUP } }
    }
}
