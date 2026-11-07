package cz.calendar

import android.accounts.Account
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric for the real Account and Build classes
@RunWith(AndroidJUnit4::class)
class SyncCheckTest {

    private val syncingAccount = AccountSync(Account("ja@gmail.com", GOOGLE_ACCOUNT), syncable = true, automatic = true)
    private val working = SyncFacts(adapter = true, appInstalled = true, appEnabled = true, autoSync = true, accounts = listOf(syncingAccount), googleCalendars = 3)
    private val nothingCame = working.copy(googleCalendars = 0)

    @Test
    fun everythingInPlaceIsNoProblem() {
        assertNull(syncProblem(working))
    }

    @Test
    fun missingOrSwitchedOffGoogleCalendarComesFirst() {
        assertEquals(SyncProblem.APP_MISSING, syncProblem(nothingCame.copy(adapter = false, appInstalled = false, autoSync = false)))
        assertEquals(SyncProblem.APP_OFF, syncProblem(nothingCame.copy(adapter = false, appEnabled = false)))
        assertEquals(SyncProblem.ADAPTER_OFF, syncProblem(nothingCame.copy(adapter = false)))
    }

    @Test
    fun hiddenSyncPartIsFineWhenCalendarsCome() {
        assertNull(syncProblem(working.copy(adapter = false)))
        assertNull(syncProblem(working.copy(adapter = false, appInstalled = false)))
    }

    @Test
    fun thenAutoSyncThenTheAccountsOwnSwitch() {
        assertEquals(SyncProblem.AUTO_SYNC_OFF, syncProblem(nothingCame.copy(autoSync = false)))
        assertEquals(SyncProblem.ACCOUNT_OFF, syncProblem(nothingCame.copy(accounts = listOf(syncingAccount.copy(automatic = false)))))
        assertEquals(SyncProblem.ACCOUNT_OFF, syncProblem(nothingCame.copy(accounts = listOf(syncingAccount.copy(syncable = false)))))
    }

    // Phone syncing, account on, still nothing: Google Calendar keeps its data from other apps
    @Test
    fun syncingAccountWithoutCalendarsMeansSharingIsOff() {
        assertEquals(SyncProblem.SHARING_OFF, syncProblem(nothingCame))
    }

    @Test
    fun noKnownAccountIsNotAWarning() {
        assertNull(syncProblem(nothingCame.copy(accounts = emptyList())))
    }

    @Test
    fun reportTellsTheFactsAndTheConclusion() {
        val calendars = listOf(CalendarInfo(1, "Doma", LOCAL_ACCOUNT, 0, writable = true, primary = false, accountType = "LOCAL"))

        val report = describeSync(nothingCame, calendars)

        assertTrue(report, "ja@gmail.com: synchronizace Kalendáře zapnutá" in report)
        assertTrue(report, "Kalendáře v telefonu: 1, z toho Google: 0" in report)
        assertTrue(report, "Doma (Tento telefon, LOCAL)" in report)
        assertTrue(report, SyncProblem.SHARING_OFF.text in report)
    }
}
