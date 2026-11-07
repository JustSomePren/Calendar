package cz.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract

const val GOOGLE_ACCOUNT = "com.google"

// System picker with the Google accounts already on the phone, plus adding a new one.
// Picking an existing account needs no new sign-in and lets the app see that account.
fun chooseAccountIntent(): Intent =
    AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT), null, null, null, null)

// The phone's own Google sign-in, for an account that is not on the phone yet
fun addGoogleAccountIntent(): Intent =
    Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT))

// Turns on calendar sync for the picked account and starts it, so its calendars show up.
// Returns the account, or null when nothing was picked.
fun syncPickedAccount(data: Intent?): Account? {
    val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return null
    val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: GOOGLE_ACCOUNT
    val account = Account(name, type)

    ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
    ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
    requestCalendarSync(account)
    return account
}

// Null when the Google Calendar app is not installed
fun googleCalendarApp(context: Context): ApplicationInfo? =
    runCatching { context.packageManager.getApplicationInfo(GOOGLE_CALENDAR_APP, 0) }.getOrNull()

const val GOOGLE_CALENDAR_APP = "com.google.android.calendar"

fun requestCalendarSync(account: Account) {
    val now = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }
    ContentResolver.requestSync(account, CalendarContract.AUTHORITY, now)
}
