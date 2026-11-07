package cz.calendar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric does not pass touches into the drawer sheet or dialogs, so clicks go via accessibility
@RunWith(AndroidJUnit4::class)
class CalendarDrawerTest {

    @get:Rule
    val compose = createComposeRule()

    private val calendars = listOf(
        CalendarInfo(1, "Rodina", "ja@gmail.com", 0xFF039BE5.toInt(), writable = true, primary = true),
        CalendarInfo(2, "Práce", "ja@firma.cz", 0xFF33B679.toInt(), writable = true, primary = false),
        CalendarInfo(3, "Tým", "ja@firma.cz", 0xFF8E24AA.toInt(), writable = true, primary = false, synced = false),
    )

    @Test
    fun listsAccountsWithCalendarsAndActions() {
        val toggled = mutableListOf<Long>()
        val actions = mutableListOf<String>()
        compose.setContent {
            CalendarDrawer(
                calendars,
                hidden = emptySet(),
                onToggle = { toggled += it.id },
                onAddAccount = { actions += "account" },
                onNewCalendar = { actions += "calendar" },
                onSettings = { actions += "settings" },
            )
        }

        compose.onNodeWithText("Připojené účty").assertExists()
        compose.onNodeWithText("ja@gmail.com").assertExists()
        compose.onNodeWithText("ja@firma.cz").assertExists()
        compose.onNodeWithText("Účty a synchronizace").assertDoesNotExist()

        // A calendar the phone does not sync yet says how to switch it on
        compose.onNodeWithText("Tým").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Práce").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(3L, 2L), toggled)

        compose.onNodeWithText("Přidat účet").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Nový kalendář v telefonu").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Nastavení").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("account", "calendar", "settings"), actions)
    }

    @Test
    fun unsyncedCalendarIsMarked() {
        compose.setContent { CalendarDrawer(calendars, emptySet(), {}, {}, {}, {}) }
        compose.onNodeWithText("Nesynchronizuje se – zaškrtnutím zapneš").assertExists()
    }

    @Test
    fun emptyPhoneSuggestsAccountOrLocalCalendar() {
        compose.setContent { CalendarDrawer(emptyList(), emptySet(), {}, {}, {}, {}) }
        compose.onNodeWithText("V telefonu zatím není žádný kalendář. Přidej účet, nebo založ kalendář v telefonu.").assertExists()
    }

    // The fields alone: a dialog is its own window, which Robolectric does not settle reliably
    @Test
    fun newCalendarFieldsTakeNameAndColor() {
        var name by mutableStateOf("")
        var color by mutableIntStateOf(0)
        compose.setContent { NewCalendarFields(name, { name = it }, color, { color = it }) }

        compose.onNode(hasSetTextAction()).performTextReplacement("Domácnost")
        compose.onAllNodesWithContentDescription("Barva")[2].performClick()
        compose.waitForIdle()

        assertEquals("Domácnost", name)
        assertEquals(0xFFE67C73.toInt(), color)
        compose.onNodeWithText("Kalendář zůstane jen v tomto telefonu, nikam se nesynchronizuje a nezálohuje.").assertExists()
    }
}
