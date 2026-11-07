package cz.calendar

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarDrawerTest {

    @get:Rule
    val compose = createComposeRule()

    private val calendars = listOf(
        CalendarInfo(1, "Rodina", "ja@gmail.com", 0xFF039BE5.toInt(), writable = true, primary = true),
        CalendarInfo(2, "Práce", "ja@firma.cz", 0xFF33B679.toInt(), writable = true, primary = false),
        CalendarInfo(3, "Narozeniny", "ja@gmail.com", 0xFF8E24AA.toInt(), writable = false, primary = false),
    )

    @Test
    fun showsCalendarsByAccountAndTogglesThem() {
        var hidden by mutableStateOf(emptySet<Long>())
        var opened: Intent? = null
        var addAccount = 0
        compose.setContent {
            CalendarDrawer(
                calendars,
                hidden,
                onToggle = { id -> hidden = if (id in hidden) hidden - id else hidden + id },
                onOpen = { opened = it },
                onAddAccount = { addAccount++ },
            )
        }

        compose.onNodeWithText("ja@gmail.com").assertExists()
        compose.onNodeWithText("ja@firma.cz").assertExists()

        // Robolectric does not pass touches into the drawer sheet, click via accessibility
        compose.onNodeWithText("Práce").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(setOf(2L), hidden)

        compose.onNodeWithText("Přidat účet").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, addAccount)

        compose.onNodeWithText("Účty a synchronizace").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(android.provider.Settings.ACTION_SYNC_SETTINGS, opened?.action)
    }

    @Test
    fun emptyPhoneSuggestsAddingAccount() {
        compose.setContent { CalendarDrawer(emptyList(), emptySet(), onToggle = {}, onOpen = {}, onAddAccount = {}) }
        compose.onNodeWithText("V telefonu zatím není žádný kalendář. Přidej účet Google.").assertExists()
    }
}
