package cz.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val GOOGLE_ACCOUNT = "com.google"

// Google accounts already on the phone. Needs the GET_ACCOUNTS permission;
// without it (or on a phone that hides them) the list comes back empty.
fun googleAccounts(context: Context): List<Account> = runCatching {
    AccountManager.get(context).getAccountsByType(GOOGLE_ACCOUNT).orEmpty().toList()
}.getOrDefault(emptyList())

// The phone's own Google sign-in, for an account that is not on the phone yet
fun addGoogleAccountIntent(): Intent =
    Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT))

// False when no activity on the phone handles the intent; launching it then would do nothing or crash
fun canStart(context: Context, intent: Intent): Boolean =
    intent.resolveActivity(context.packageManager) != null

// Turns on calendar sync for the account and starts it, so its calendars show up.
// Suspend, the resolver calls run on a background thread.
suspend fun syncAccount(account: Account) = withContext(Dispatchers.IO) {
    ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
    ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
    requestCalendarSync(account)
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
