package cz.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowContentResolver

@RunWith(AndroidJUnit4::class)
class AccountSyncTest {

    @Test
    fun accountGetsCalendarSyncOnAndStarted() = runBlocking {
        val account = Account("ja@firma.cz", GOOGLE_ACCOUNT)

        syncAccount(account)

        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY))
        assertTrue(ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY))
        assertTrue(ShadowContentResolver.getStatus(account, CalendarContract.AUTHORITY).syncRequests > 0)
    }

    @Test
    fun addAccountIntentAsksThePhoneForGoogle() {
        val intent = addGoogleAccountIntent()

        assertEquals(Settings.ACTION_ADD_ACCOUNT, intent.action)
        assertEquals(listOf(GOOGLE_ACCOUNT), intent.getStringArrayExtra(Settings.EXTRA_ACCOUNT_TYPES)?.toList())
    }

    @Test
    fun phoneWithoutGoogleAccountsListsNone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(googleAccounts(context).isEmpty())
    }
}
