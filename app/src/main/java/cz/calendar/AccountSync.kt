package cz.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract

const val GOOGLE_ACCOUNT = "com.google"

// System picker with the Google accounts already on the phone, plus adding a new one.
// Picking an existing account needs no new sign-in and lets the app see that account.
fun chooseAccountIntent(): Intent =
    AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT), null, null, null, null)

// Turns on calendar sync for the picked account and starts it, so its calendars show up.
// Returns the account, or null when nothing was picked.
fun syncPickedAccount(data: Intent?): Account? {
    val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return null
    val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: GOOGLE_ACCOUNT
    val account = Account(name, type)

    ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
    ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
    val now = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }
    ContentResolver.requestSync(account, CalendarContract.AUTHORITY, now)
    return account
}
