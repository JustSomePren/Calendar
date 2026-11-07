package cz.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowContentResolver

@RunWith(AndroidJUnit4::class)
class AccountSyncTest {

    @Test
    fun pickedAccountGetsCalendarSyncOnAndStarted() {
        val picked = Intent()
            .putExtra(AccountManager.KEY_ACCOUNT_NAME, "ja@firma.cz")
            .putExtra(AccountManager.KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT)

        val account = syncPickedAccount(picked)

        assertEquals(Account("ja@firma.cz", GOOGLE_ACCOUNT), account)
        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY))
        assertTrue(ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY))
        assertTrue(ShadowContentResolver.getStatus(account, CalendarContract.AUTHORITY).syncRequests > 0)
    }

    @Test
    fun cancelledPickerChangesNothing() {
        assertNull(syncPickedAccount(null))
        assertNull(syncPickedAccount(Intent()))
    }

    @Test
    fun pickerOffersGoogleAccounts() {
        val intent = chooseAccountIntent()
        assertEquals(listOf(GOOGLE_ACCOUNT), intent.getStringArrayExtra("allowableAccountTypes")?.toList())
    }
}
