package cz.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowContentResolver

@RunWith(AndroidJUnit4::class)
class AccountSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = Account("ja@firma.cz", GOOGLE_ACCOUNT)

    @Test
    fun accountGetsCalendarSyncOnAndStarted() = runBlocking {
        syncAccount(account)

        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY))
        assertTrue(ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY))
        assertTrue(ShadowContentResolver.getStatus(account, CalendarContract.AUTHORITY).syncRequests > 0)
    }

    @Test
    fun pickedAccountComesFromTheChooserResult() {
        val picked = Intent()
            .putExtra(AccountManager.KEY_ACCOUNT_NAME, "ja@firma.cz")
            .putExtra(AccountManager.KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT)

        assertEquals(account, pickedAccount(picked))
        assertNull(pickedAccount(null))
        assertNull(pickedAccount(Intent()))
    }

    @Test
    fun chooserOffersGoogleAccounts() {
        val intent = chooseAccountIntent()
        assertEquals(listOf(GOOGLE_ACCOUNT), intent.getStringArrayExtra("allowableAccountTypes")?.toList())
    }

    @Test
    fun addAccountIntentAsksThePhoneForGoogle() {
        val intent = addGoogleAccountIntent()

        assertEquals(Settings.ACTION_ADD_ACCOUNT, intent.action)
        assertEquals(listOf(GOOGLE_ACCOUNT), intent.getStringArrayExtra(Settings.EXTRA_ACCOUNT_TYPES)?.toList())
    }

    // The phone lists no accounts to apps, so the app remembers the picked ones and reads the calendars
    @Test
    fun knownAccountsComeFromPicksAndCalendarsWithoutDoubles() {
        val calendars = listOf(
            CalendarInfo(1, "Rodina", "Ja@Gmail.com", 0, writable = true, primary = true, accountType = GOOGLE_ACCOUNT),
            CalendarInfo(2, "Doma", LOCAL_ACCOUNT, 0, writable = true, primary = false, accountType = CalendarContract.ACCOUNT_TYPE_LOCAL),
        )

        val known = knownGoogleAccounts(context, calendars, setOf("ja@gmail.com", "ja@firma.cz"))

        assertEquals(listOf("ja@gmail.com", "ja@firma.cz"), known.map { it.name })
        assertTrue(knownGoogleAccounts(context, emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun accountCalendarsMatchWhateverLetterCaseGoogleUses() {
        val calendars = listOf(
            CalendarInfo(1, "Rodina", "Ja@Gmail.com", 0, writable = true, primary = true, accountType = GOOGLE_ACCOUNT),
            CalendarInfo(2, "Práce", "ja@firma.cz", 0, writable = true, primary = false, accountType = GOOGLE_ACCOUNT),
        )

        assertEquals(listOf(1L), calendarsOf(calendars, Account("ja@gmail.com", GOOGLE_ACCOUNT)).map { it.id })
        assertTrue(calendarsOf(calendars, Account("ja@gmail.com", "com.other")).isEmpty())
    }

    // Nothing syncs on the test phone: the wait gives the sync its moment to start and then ends
    @Test
    fun waitingForSyncEndsWhenNothingRuns() = runBlocking {
        val started = System.currentTimeMillis()
        awaitSync(account, maxMillis = 5_000, startWithinMillis = 600)
        val took = System.currentTimeMillis() - started
        assertTrue("took $took ms", took in 500..4_000)
    }
}
