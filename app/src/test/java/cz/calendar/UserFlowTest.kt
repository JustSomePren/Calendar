package cz.calendar

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.provider.CalendarContract.Events
import android.widget.TextView
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsNotEnabled
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
        FakeCalendarProvider.addCalendar(2, "Práce", account = "ja@firma.cz")
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
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
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
        // The watch choice is stored last, after it everything is saved
        waitFor { settings.watch(id) == Watch.ALWAYS }
        assertEquals(Priority.HIGH, settings.priority(id))
        assertEquals(1L, settings.lastCalendar)
        assertTrue(settings.isCreated(id))
    }

    @Test
    fun unsavedEditsSurviveRotation() {
        ActivityScenario.launch<EventEditActivity>(editor()).use { scenario ->
            waitForText("Nová událost")
            field("Název").performTextInput("Kadeřník")
            choose("Normální", "Vysoká")
            scenario.recreate()
            waitForText("Kadeřník")
            compose.onNode(hasClickAction() and hasText("Vysoká")).assertExists()
            compose.onNodeWithText("Uložit").performClick()
            waitFor { FakeCalendarProvider.events.isNotEmpty() }
        }
        assertEquals("Kadeřník", FakeCalendarProvider.events.single()[Events.TITLE])
    }

    @Test
    fun doubleTapOnSaveWritesOnce() {
        ActivityScenario.launch<EventEditActivity>(editor()).use {
            waitForText("Nová událost")
            field("Název").performTextInput("Kadeřník")
            val save = compose.onNodeWithText("Uložit")
            compose.runOnUiThread {
                save.performSemanticsAction(SemanticsActions.OnClick)
                save.performSemanticsAction(SemanticsActions.OnClick)
            }
            waitFor { FakeCalendarProvider.events.isNotEmpty() }
            compose.waitForIdle()
        }
        assertEquals(1, FakeCalendarProvider.events.size)
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
    fun mutingOneOccurrenceMutesSeriesWithoutTouchingCalendar() {
        val weekly = addEvent("Úklid", mapOf(Events.RRULE to "FREQ=WEEKLY", Events.DTEND to null, Events.DURATION to "P3600S"))

        ActivityScenario.launch<EventEditActivity>(editor(weekly, at(9))).use {
            waitForText("Osobní kalendář: upozorní.")
            choose("Podle kalendáře", "Nikdy")
            compose.onNodeWithText("Uložit").performClick()
            waitFor { settings.watch(weekly) == Watch.NEVER }
        }
        assertEquals(1, FakeCalendarProvider.events.size)
    }

    @Test
    fun dayPanelShowsDatesOfLongEvents() {
        addEvent("Konference Brno", mapOf(Events.DTSTART to at(9, today.minusDays(1)), Events.DTEND to at(17, today.plusDays(1))))
        val intent = Intent(app, MainActivity::class.java).putExtra(EXTRA_DAY, today.toString())
        val format = java.time.format.DateTimeFormatter.ofPattern("d. M.")

        ActivityScenario.launch<MainActivity>(intent).use {
            waitForText("Konference Brno")
            waitForText("${today.minusDays(1).format(format)} 09:00 – ${today.plusDays(1).format(format)} 17:00")
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
    fun busyDayKeepsAddButtonInDayPanel() {
        for (i in 0 until 16) addEvent("Schůzka $i", mapOf(Events.DTSTART to at(6) + i * 1800_000L, Events.DTEND to at(6) + (i + 1) * 1800_000L))
        val intent = Intent(app, MainActivity::class.java).putExtra(EXTRA_DAY, today.toString())

        ActivityScenario.launch<MainActivity>(intent).use {
            waitForText("Schůzka 0")
            val button = compose.onNode(hasClickAction() and hasText("Přidat událost")).fetchSemanticsNode()
            assertTrue(button.boundsInWindow.height > 0f)
        }
    }

    @Test
    fun changedOccurrenceCannotRepeatAndCanBeCancelled() {
        val series = addEvent("Úklid", mapOf(Events.RRULE to "FREQ=WEEKLY", Events.DTEND to null, Events.DURATION to "P3600S"))
        val changed = addEvent("Úklid jindy", mapOf(Events.ORIGINAL_ID to series, Events.ORIGINAL_INSTANCE_TIME to at(9)))

        ActivityScenario.launch<EventEditActivity>(editor(changed)).use {
            waitForText("Samostatně změněný výskyt opakované události, úpravy platí jen pro něj.")
            compose.onNode(hasClickAction() and hasText("Neopakovat")).assertIsNotEnabled()
            compose.onNodeWithContentDescription("Smazat").performClick()
            waitForText("Jen tento výskyt")
            compose.onNodeWithText("Jen tento výskyt").performClick()
            waitFor { FakeCalendarProvider.event(changed)[Events.STATUS] == Events.STATUS_CANCELED }
        }
        assertEquals("FREQ=WEEKLY", FakeCalendarProvider.event(series)[Events.RRULE])
    }

    @Test
    fun rotationDoesNotReopenDayFromWidget() {
        addEvent("Zubař")
        val intent = Intent(app, MainActivity::class.java).putExtra(EXTRA_DAY, today.plusMonths(3).toString())

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitForText("Přidat událost")
            scenario.recreate()
            waitForText(monthTitle(java.time.YearMonth.now().plusMonths(3)))
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("Přidat událost").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp", fontScale = 2.0f)
    fun largeFontKeepsDayNumbersAndEventsWhole() {
        addEvent("Zubař")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Zubař")
            val number = compose.onAllNodesWithText(today.dayOfMonth.toString())
                .fetchSemanticsNodes().first { it.layoutInfo.isPlaced }
            val chip = compose.onAllNodesWithText("Zubař").fetchSemanticsNodes().first()
            // Boxes grow with the font, so the text is not cut in half
            val density = app.resources.displayMetrics.density
            assertTrue(number.size.height >= 22 * 2.0f * density * 0.9f)
            assertTrue(chip.size.height >= 16 * 2.0f * density * 0.9f)
        }
    }

    @Test
    fun leavingAllDayGivesUsualTimes() {
        val id = FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to 1L, Events.TITLE to "Výlet", Events.ALL_DAY to 1,
                Events.DTSTART to today.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                Events.DTEND to today.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
            )
        )

        ActivityScenario.launch<EventEditActivity>(editor(id)).use {
            waitForText("Výlet")
            compose.onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
            compose.onNodeWithText("Uložit").performClick()
            waitFor { FakeCalendarProvider.event(id)[Events.ALL_DAY] == 0 }
        }
        assertEquals(at(9), FakeCalendarProvider.event(id)[Events.DTSTART])
        assertEquals(at(10), FakeCalendarProvider.event(id)[Events.DTEND])
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
            // On a phone watching the calendar without access throws, Robolectric only lets us check it did not try
            val observers = Shadows.shadowOf(app.contentResolver).getContentObservers(android.provider.CalendarContract.CONTENT_URI)
            assertTrue(observers.isEmpty())
            compose.onNodeWithText("Otevřít nastavení aplikace").performClick()
            scenario.onActivity { activity ->
                val started = Shadows.shadowOf(activity).nextStartedActivity
                assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
            }
        }
    }

    @Test
    fun accessAppearingOnReturnPlansReminders() {
        // Robolectric cannot drive the permission dialog, so start with access, as on return from system
        // settings: the first resume that has access must plan reminders right away
        val now = System.currentTimeMillis()
        addEvent("Zubař", mapOf(Events.DTSTART to now + 5 * 3600_000L, Events.DTEND to now + 6 * 3600_000L))

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Zubař")
        }
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        waitFor { alarms.scheduledAlarms.any { it.getType() == AlarmManager.RTC_WAKEUP } }
    }

    @Test
    fun todayMovesWhenDateChangesWhileOpen() {
        val previous = java.util.TimeZone.getDefault()
        // Far east is already tomorrow, far west still yesterday: switching between them changes the date
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val before = LocalDate.now()
                waitForText(monthTitle(java.time.YearMonth.now()))
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Pago_Pago"))
                app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
                val after = LocalDate.now()
                assertTrue(after != before)
                val today = androidx.compose.ui.test.hasStateDescription("dnes")
                waitFor {
                    compose.onAllNodes(today and hasText(after.dayOfMonth.toString()), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(
                    compose.onAllNodes(today and hasText(before.dayOfMonth.toString()), useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty(),
                )
            }
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun withAccessAppWatchesCalendar() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(monthTitle(java.time.YearMonth.now()))
            val observers = Shadows.shadowOf(app.contentResolver).getContentObservers(android.provider.CalendarContract.CONTENT_URI)
            assertTrue(observers.isNotEmpty())
        }
    }

    @Test
    fun editorClosesWithoutAccess() {
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR)

        val scenario = ActivityScenario.launch<EventEditActivity>(editor())
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun importGoesToChosenCalendarAfterRecreation() {
        val file = java.io.File(app.cacheDir, "porada.ics").apply {
            writeText("BEGIN:VEVENT\r\nSUMMARY:Porada\r\nDTSTART:${today.toString().replace("-", "")}T100000\r\nEND:VEVENT")
        }

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            waitForText("Importovat do")
            compose.onNode(hasClickAction() and hasText("Rodina")).performScrollTo().performClick()
            compose.onNode(hasClickAction() and hasText("Práce")).performClick()
            compose.onNodeWithText("Vybrat soubory .ics").performScrollTo().performClick()
            var requestCode = 0
            scenario.onActivity { requestCode = Shadows.shadowOf(it).nextStartedActivityForResult.requestCode }

            scenario.recreate()
            scenario.onActivity {
                it.activityResultRegistry.dispatchResult(requestCode, android.app.Activity.RESULT_OK, Intent().setData(android.net.Uri.fromFile(file)))
            }
            waitFor { FakeCalendarProvider.events.isNotEmpty() }
        }
        assertEquals(2L, FakeCalendarProvider.events.single()[Events.CALENDAR_ID])
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
