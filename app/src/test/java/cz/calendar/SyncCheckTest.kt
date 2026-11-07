package cz.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncCheckTest {

    private val working = SyncFacts(adapter = true, appInstalled = true, appEnabled = true, autoSync = true, googleCalendars = 3)

    @Test
    fun everythingInPlaceIsNoProblem() {
        assertNull(syncProblem(working))
    }

    @Test
    fun missingOrSwitchedOffGoogleCalendarComesFirst() {
        assertEquals(SyncProblem.APP_MISSING, syncProblem(working.copy(adapter = false, appInstalled = false, autoSync = false, googleCalendars = 0)))
        assertEquals(SyncProblem.APP_OFF, syncProblem(working.copy(adapter = false, appEnabled = false, googleCalendars = 0)))
    }

    @Test
    fun hiddenSyncPartIsFineWhenCalendarsCome() {
        assertNull(syncProblem(working.copy(adapter = false)))
        assertNull(syncProblem(working.copy(adapter = false, appInstalled = false)))
    }

    @Test
    fun thenAutoSyncThenTheAccountsOwnSwitch() {
        assertEquals(SyncProblem.AUTO_SYNC_OFF, syncProblem(working.copy(autoSync = false, googleCalendars = 0)))
        assertEquals(SyncProblem.ACCOUNT_OFF, syncProblem(working.copy(googleCalendars = 0)))
    }
}
