package cz.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val GOOGLE_ACCOUNT = "com.google"

// System chooser with the Google accounts already on the phone. The picked account is also
// marked visible to this app, which getAccountsByType needs on Android 8+.
fun chooseAccountIntent(): Intent =
    AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT), null, null, null, null)

// Google accounts on the phone that are visible to this app (GET_ACCOUNTS plus shared through
// the chooser); on Android 8+ accounts never picked for this app are not in this list
fun googleAccounts(context: Context): List<Account> = runCatching {
    AccountManager.get(context).getAccountsByType(GOOGLE_ACCOUNT).orEmpty().toList()
}.getOrDefault(emptyList())

fun accountFrom(name: String?, type: String?): Account? =
    name?.let { Account(it, type ?: GOOGLE_ACCOUNT) }

// The account picked in the system chooser, or null when nothing was picked
fun pickedAccount(data: Intent?): Account? =
    accountFrom(data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME), data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE))

// The phone's own Google sign-in screen, for an account that is not on the phone yet
fun addGoogleAccountIntent(): Intent =
    Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT))

// False when no activity on the phone handles the intent; launching it then would do nothing or crash
fun canStart(context: Context, intent: Intent): Boolean =
    intent.resolveActivity(context.packageManager) != null

// Adds a new Google account through AccountManager: shows Google's own sign-in in the app and
// calls [onResult] with the new account (null when it failed or was cancelled). When the
// in-app flow cannot start, [onFallback] runs instead (the system add-account screen).
fun addGoogleAccount(context: Context, activity: Activity, onResult: (Account?) -> Unit, onFallback: () -> Unit) {
    val started = runCatching {
        AccountManager.get(context).addAccount(
            GOOGLE_ACCOUNT, null, null, null, activity,
            { future ->
                val extras: Bundle? = runCatching { future.result }.getOrNull()
                onResult(accountFrom(extras?.getString(AccountManager.KEY_ACCOUNT_NAME), extras?.getString(AccountManager.KEY_ACCOUNT_TYPE)))
            },
            Handler(Looper.getMainLooper()),
        )
    }.getOrNull()
    if (started == null) onFallback()
}

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
