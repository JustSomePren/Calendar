package cz.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings as SystemSettings

const val GOOGLE_SYNC_ADAPTER_APP = "com.google.android.syncadapters.calendar"

// Why Google calendars do not reach the phone, in the order they have to be fixed.
// Once nothing of this is in the way the calendars come by themselves.
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
    ADAPTER_OFF(
        "Google Kalendář je v telefonu, ale jeho synchronizační část (Google Calendar Sync) je vypnutá, " +
            "takže se žádné Google kalendáře nestahují. Zapni ji v detailu aplikace.",
        "Zapnout synchronizaci Google Kalendáře",
    ),
    AUTO_SYNC_OFF(
        "V telefonu je vypnutá automatická synchronizace účtů, proto se kalendáře nestahují.",
        "Zapnout synchronizaci",
    ),
    ACCOUNT_OFF(
        "Google účet v telefonu nemá zapnutou synchronizaci Kalendáře. Zapni ji a kalendáře se objeví samy.",
        "Zapnout synchronizaci Kalendáře",
    ),
    SHARING_OFF(
        "Telefon synchronizuje, ale aplikace Google Kalendář svá data nedává dalším aplikacím. " +
            "Od roku 2025 to má jako volbu: otevři Google Kalendář → ☰ → Nastavení → Obecné " +
            "a zapni „Sdílet data z Kalendáře Google s dalšími aplikacemi“. Pak se sem vrať, kalendáře dorazí samy.",
        "Otevřít Google Kalendář",
    ),
}

// Calendar sync of one Google account, as the phone's settings have it
data class AccountSync(val account: Account, val syncable: Boolean, val automatic: Boolean) {
    val on: Boolean get() = syncable && automatic
}

// What the phone says about syncing Google calendars
data class SyncFacts(
    // The phone has something that syncs Google calendars (Android may hide it from other apps)
    val adapter: Boolean,
    val appInstalled: Boolean,
    val appEnabled: Boolean,
    val autoSync: Boolean,
    // Google accounts this app knows about; the phone does not tell about the others
    val accounts: List<AccountSync> = emptyList(),
    val googleCalendars: Int,
)

fun syncProblem(facts: SyncFacts): SyncProblem? = when {
    // Google calendars that already came prove the syncing part works, whatever Android shows
    !facts.adapter && facts.googleCalendars == 0 && !facts.appInstalled -> SyncProblem.APP_MISSING
    !facts.adapter && facts.googleCalendars == 0 && !facts.appEnabled -> SyncProblem.APP_OFF
    !facts.adapter && facts.googleCalendars == 0 -> SyncProblem.ADAPTER_OFF
    !facts.autoSync -> SyncProblem.AUTO_SYNC_OFF
    facts.googleCalendars > 0 -> null
    // No account known yet: "Přidat účet" is the way, not a warning
    facts.accounts.isEmpty() -> null
    facts.accounts.none { it.on } -> SyncProblem.ACCOUNT_OFF
    else -> SyncProblem.SHARING_OFF
}

fun readSyncFacts(context: Context, calendars: List<CalendarInfo>, remembered: Set<String>): SyncFacts {
    val app = googleCalendarApp(context)
    return SyncFacts(
        adapter = runCatching {
            ContentResolver.getSyncAdapterTypes().orEmpty().any { it.authority == CalendarContract.AUTHORITY && it.accountType == GOOGLE_ACCOUNT }
        }.getOrDefault(false),
        appInstalled = app != null,
        appEnabled = app?.enabled == true,
        autoSync = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true),
        accounts = knownGoogleAccounts(context, calendars, remembered).map { account ->
            AccountSync(
                account,
                syncable = runCatching { ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY) > 0 }.getOrDefault(false),
                automatic = runCatching { ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY) }.getOrDefault(false),
            )
        },
        googleCalendars = calendars.count { it.accountType == GOOGLE_ACCOUNT },
    )
}

// One tap that fixes the problem, or opens the exact screen where the user does it
suspend fun fixSync(context: Context, problem: SyncProblem, facts: SyncFacts) {
    val intent = when (problem) {
        SyncProblem.APP_MISSING -> Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_CALENDAR_APP"))
        SyncProblem.APP_OFF -> appDetails(GOOGLE_CALENDAR_APP)
        // On Pixels the syncing part is its own app, elsewhere it is inside Google Calendar
        SyncProblem.ADAPTER_OFF -> appDetails(if (isInstalled(context, GOOGLE_SYNC_ADAPTER_APP)) GOOGLE_SYNC_ADAPTER_APP else GOOGLE_CALENDAR_APP)
        SyncProblem.AUTO_SYNC_OFF -> {
            runCatching { ContentResolver.setMasterSyncAutomatically(true) }
            return
        }
        SyncProblem.ACCOUNT_OFF -> {
            facts.accounts.forEach { runCatching { syncAccount(it.account) } }
            return
        }
        SyncProblem.SHARING_OFF -> context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR_APP) ?: appDetails(GOOGLE_CALENDAR_APP)
    }
    runCatching { context.startActivity(intent) }
}

private fun appDetails(packageName: String) =
    Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

private fun isInstalled(context: Context, packageName: String) =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

// Everything above as text, for the diagnostics in settings and for asking for help
fun describeSync(facts: SyncFacts, calendars: List<CalendarInfo>): String = buildString {
    fun yes(value: Boolean) = if (value) "ano" else "ne"
    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Google Kalendář nainstalován: ${yes(facts.appInstalled)}, zapnutý: ${yes(facts.appEnabled)}")
    appendLine("Synchronizace Google kalendářů v telefonu (sync adapter): ${yes(facts.adapter)}")
    appendLine("Automatická synchronizace účtů: ${yes(facts.autoSync)}")
    appendLine("Známé Google účty: ${facts.accounts.size}")
    facts.accounts.forEach { appendLine("  ${it.account.name}: synchronizace Kalendáře ${if (it.on) "zapnutá" else "vypnutá"}") }
    appendLine("Kalendáře v telefonu: ${calendars.size}, z toho Google: ${facts.googleCalendars}")
    calendars.forEach { appendLine("  ${it.name} (${it.account}, ${it.accountType}${if (it.synced) "" else ", nesynchronizuje se"})") }
    val problem = syncProblem(facts)
    appendLine("Závěr: ${problem?.text ?: "Google kalendáře se stahují v pořádku."}")
}
