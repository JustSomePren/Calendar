package cz.calendar

import android.content.Intent
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AppUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val today = LocalDate.now()
    private var dentist = 0L

    @Before
    fun setUp() {
        setUpFakeCalendar()
        FakeCalendarProvider.addCalendar(1, "Osobní")
        val begin = today.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dentist = FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to 1L,
                Events.TITLE to "Zubař",
                Events.EVENT_LOCATION to "Praha",
                Events.DTSTART to begin,
                Events.DTEND to begin + 3600_000L,
                Events.ALL_DAY to 0,
            )
        )
    }

    @Test
    fun monthShowsEvent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Zubař").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(monthTitle(java.time.YearMonth.now())).assertIsDisplayed()
        }
    }

    @Test
    fun dayFromWidgetOpensDayPanel() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(EXTRA_DAY, today.toString())
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Přidat událost").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("09:00 – 10:00").assertExists()
            // Panel row plus the month grid (the pager also keeps the neighbouring month ready)
            assertTrue(compose.onAllNodesWithText("Zubař").fetchSemanticsNodes().size >= 2)
        }
    }

    @Test
    fun editorShowsEventAndPriority() {
        Settings(ApplicationProvider.getApplicationContext()).setPriority(dentist, Priority.HIGH)
        val intent = Intent(ApplicationProvider.getApplicationContext(), EventEditActivity::class.java)
            .putExtra(EXTRA_EVENT_ID, dentist)
        ActivityScenario.launch<EventEditActivity>(intent).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Zubař").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Praha").assertExists()
            compose.onNodeWithText("Vysoká").assertExists()
            compose.onNodeWithText("Podle kalendáře").assertExists()
            compose.onNodeWithText("Osobní kalendář: upozorní.").assertExists()
            compose.onNodeWithText("Účastníci").assertExists()
        }
    }
}
