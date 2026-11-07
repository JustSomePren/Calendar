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
import android.provider.Settings as SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val GOOGLE_ACCOUNT = "com.google"
const val GOOGLE_CALENDAR_APP = "com.google.android.calendar"

// How Google calendars get into the phone: a Google account in the phone's settings has
// Calendar sync on, the Google Calendar app downloads the calendars into the phone's calendar
// store, and every app with calendar access (this one too) reads them there. So this app does
// not sign in anywhere; "adding an account" only makes sure that chain is switched on.

// System chooser with the Google accounts already on the phone. Since Android 8 an app sees
// only the accounts the user picked for it here, so this is the one way to list them.
fun chooseAccountIntent(): Intent =
    AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT), null, null, null, null)

// The account picked in the chooser, or null when nothing was picked
fun pickedAccount(data: Intent?): Account? =
    data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { Account(it, data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: GOOGLE_ACCOUNT) }

// Google accounts this app knows about: shared with it in the chooser (now or earlier) or
// already having calendars in the phone
fun knownGoogleAccounts(context: Context, calendars: List<CalendarInfo>, remembered: Set<String>): List<Account> {
    val visible = runCatching { AccountManager.get(context).getAccountsByType(GOOGLE_ACCOUNT).map { it.name } }.getOrDefault(emptyList())
    val withCalendars = calendars.filter { it.accountType == GOOGLE_ACCOUNT }.map { it.account }
    return (visible + remembered + withCalendars).distinctBy { it.lowercase() }.map { Account(it, GOOGLE_ACCOUNT) }
}

// Calendars of the account that are in the phone; Google writes the name in its own letter case
fun calendarsOf(calendars: List<CalendarInfo>, account: Account): List<CalendarInfo> =
    calendars.filter { it.accountType == account.type && it.account.equals(account.name, ignoreCase = true) }

// Google's own sign-in for an account that is not on the phone yet, shown over this app.
// [onResult] gets the new account, or null when the user gave up. False when the phone
// cannot show it, then [addGoogleAccountIntent] is the way.
fun addGoogleAccount(activity: Activity, onResult: (Account?) -> Unit): Boolean = runCatching {
    AccountManager.get(activity).addAccount(
        GOOGLE_ACCOUNT, null, null, null, activity,
        { future ->
            val extras: Bundle? = runCatching { future.result }.getOrNull()
            onResult(extras?.getString(AccountManager.KEY_ACCOUNT_NAME)?.let { Account(it, extras.getString(AccountManager.KEY_ACCOUNT_TYPE) ?: GOOGLE_ACCOUNT) })
        },
        Handler(Looper.getMainLooper()),
    )
}.isSuccess

// The phone's own add-account screen, the fallback
fun addGoogleAccountIntent(): Intent =
    Intent(SystemSettings.ACTION_ADD_ACCOUNT).putExtra(SystemSettings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT))

// False when nothing on the phone handles the intent; starting it would crash
fun canStart(context: Context, intent: Intent): Boolean =
    intent.resolveActivity(context.packageManager) != null

// Switches Calendar sync on for the account and asks for a sync now. Off the main thread,
// the sync settings live in another process.
suspend fun syncAccount(account: Account) = withContext(Dispatchers.IO) {
    ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
    ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
    requestCalendarSync(account)
}

fun requestCalendarSync(account: Account) {
    val now = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }
    ContentResolver.requestSync(account, CalendarContract.AUTHORITY, now)
}

// True while the phone is syncing the account's calendars or is about to
fun syncRunning(account: Account): Boolean = runCatching {
    ContentResolver.isSyncPending(account, CalendarContract.AUTHORITY) || ContentResolver.isSyncActive(account, CalendarContract.AUTHORITY)
}.getOrDefault(false)

// Waits until the phone has finished syncing the account's calendars: gives the sync a moment
// to start, then waits for it to end. Returns after [maxMillis] at the latest.
suspend fun awaitSync(account: Account, maxMillis: Long = 90_000, startWithinMillis: Long = 10_000) = withContext(Dispatchers.IO) {
    val started = System.currentTimeMillis()
    while (System.currentTimeMillis() - started < maxMillis) {
        val elapsed = System.currentTimeMillis() - started
        if (elapsed >= startWithinMillis && !syncRunning(account)) return@withContext
        delay(500)
    }
}

// Null when the Google Calendar app is not installed
fun googleCalendarApp(context: Context): ApplicationInfo? =
    runCatching { context.packageManager.getApplicationInfo(GOOGLE_CALENDAR_APP, 0) }.getOrNull()
