package cz.calendar

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings as SystemSettings

// Why Google calendars do not reach the phone, in the order they have to be fixed.
// The phone's accounts show up by themselves once nothing of this is in the way.
enum class SyncProblem(val text: String, val button: String) {
    APP_MISSING(
        "Google kalendáře do telefonu stahuje aplikace Google Kalendář a ta v telefonu není. " +
            "Stačí ji nainstalovat, používat ji nemusíš.",
        "Nainstalovat Google Kalendář",
    ),
    APP_OFF(
        "Google kalendáře do telefonu stahuje aplikace Google Kalendář a ta je vypnutá. " +
            "Stačí ji zapnout, používat ji nemusíš.",
        "Zapnout Google Kalendář",
    ),
    AUTO_SYNC_OFF(
        "V telefonu je vypnutá automatická synchronizace účtů, proto se kalendáře nestahují.",
        "Zapnout synchronizaci",
    ),
    ACCOUNT_OFF(
        "Žádný Google účet v telefonu nemá zapnutou synchronizaci Kalendáře. " +
            "V nastavení u svého účtu zapni Kalendář, kalendáře se pak objeví samy.",
        "Otevřít synchronizaci účtů",
    ),
}

// What the phone says about syncing Google calendars
data class SyncFacts(
    val adapter: Boolean,
    val appInstalled: Boolean,
    val appEnabled: Boolean,
    val autoSync: Boolean,
    val googleCalendars: Int,
)

fun syncProblem(facts: SyncFacts): SyncProblem? = when {
    // Android may hide the sync part of another app; Google calendars that already came prove it works
    !facts.adapter && facts.googleCalendars == 0 && !facts.appInstalled -> SyncProblem.APP_MISSING
    !facts.adapter && facts.googleCalendars == 0 && !facts.appEnabled -> SyncProblem.APP_OFF
    !facts.autoSync -> SyncProblem.AUTO_SYNC_OFF
    facts.googleCalendars == 0 -> SyncProblem.ACCOUNT_OFF
    else -> null
}

fun readSyncFacts(context: Context, calendars: List<CalendarInfo>): SyncFacts {
    val app = googleCalendarApp(context)
    return SyncFacts(
        adapter = ContentResolver.getSyncAdapterTypes().orEmpty().any {
            it.authority == CalendarContract.AUTHORITY && it.accountType == GOOGLE_ACCOUNT
        },
        appInstalled = app != null,
        appEnabled = app?.enabled == true,
        autoSync = ContentResolver.getMasterSyncAutomatically(),
        googleCalendars = calendars.count { it.accountType == GOOGLE_ACCOUNT },
    )
}

// One tap that fixes the problem, or opens the exact system screen where the user does it
fun fixSync(context: Context, problem: SyncProblem) {
    val intent = when (problem) {
        SyncProblem.APP_MISSING -> Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_CALENDAR_APP"))
        SyncProblem.APP_OFF -> Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$GOOGLE_CALENDAR_APP"))
        SyncProblem.AUTO_SYNC_OFF -> {
            ContentResolver.setMasterSyncAutomatically(true)
            return
        }
        SyncProblem.ACCOUNT_OFF -> Intent(SystemSettings.ACTION_SYNC_SETTINGS)
            .putExtra(SystemSettings.EXTRA_AUTHORITIES, arrayOf(CalendarContract.AUTHORITY))
    }
    runCatching { context.startActivity(intent) }
}
