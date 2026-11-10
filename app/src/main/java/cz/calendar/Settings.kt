package cz.calendar

import android.content.Context
import org.json.JSONObject

enum class Category(val label: String, val hint: String) {
    PERSONAL("Osobní", "Upozorní na všechny události"),
    WORK("Pracovní", "Upozorní na tvoje události a na ty podle pracovního filtru"),
    QUIET("Tichý", "Jen zobrazit, neupozorňovat"),
}

// Per-event choice that overrides the calendar category
enum class Watch(val label: String) {
    AUTO("Podle kalendáře"),
    ALWAYS("Vždy (sledovat)"),
    NEVER("Nikdy"),
}

// Personal event whose photos the gallery collects into an album
data class Vacation(val album: String, val account: String? = null, val autoSend: Boolean = false)

enum class Priority(val label: String) {
    LOW("Nízká"),
    NORMAL("Normální"),
    HIGH("Vysoká"),
}

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var hiddenCalendars: Set<Long>
        get() = longSet("hidden_calendars")
        set(value) = putLongSet("hidden_calendars", value)

    var lastCalendar: Long
        get() = prefs.getLong("last_calendar", -1)
        set(value) = prefs.edit().putLong("last_calendar", value).apply()

    fun category(calendarId: Long): Category =
        prefs.getString("category_$calendarId", null)?.let { name -> Category.entries.find { it.name == name } }
            ?: Category.PERSONAL

    fun setCategory(calendarId: Long, category: Category) =
        prefs.edit().putString("category_$calendarId", category.name).apply()

    var workEmails: List<String>
        get() = splitList(prefs.getString("work_emails", "").orEmpty())
        set(value) = prefs.edit().putString("work_emails", value.joinToString(",")).apply()

    var workKeywords: List<String>
        get() = splitList(prefs.getString("work_keywords", "").orEmpty())
        set(value) = prefs.edit().putString("work_keywords", value.joinToString(",")).apply()

    fun priority(eventId: Long): Priority =
        prefs.getString("priority_$eventId", null)?.let { name -> Priority.entries.find { it.name == name } }
            ?: Priority.NORMAL

    fun setPriority(eventId: Long, priority: Priority) {
        val edit = prefs.edit()
        if (priority == Priority.NORMAL) edit.remove("priority_$eventId") else edit.putString("priority_$eventId", priority.name)
        edit.apply()
    }

    fun watch(eventId: Long): Watch =
        prefs.getString("watch_$eventId", null)?.let { name -> Watch.entries.find { it.name == name } }
            ?: Watch.AUTO

    fun setWatch(eventId: Long, watch: Watch) {
        val edit = prefs.edit()
        if (watch == Watch.AUTO) edit.remove("watch_$eventId") else edit.putString("watch_$eventId", watch.name)
        edit.apply()
    }

    // A changed occurrence follows its series unless it has its own choice
    fun watchFor(eventId: Long, seriesId: Long): Watch =
        watch(eventId).takeIf { it != Watch.AUTO || eventId == seriesId } ?: watch(seriesId)

    fun priorityFor(eventId: Long, seriesId: Long): Priority =
        priority(eventId).takeIf { it != Priority.NORMAL || eventId == seriesId } ?: priority(seriesId)

    fun eventsWithPriority(priority: Priority): Set<Long> =
        prefs.all.filter { (key, value) -> key.startsWith("priority_") && value == priority.name }
            .keys.map { it.removePrefix("priority_").toLong() }.toSet()

    fun vacation(eventId: Long): Vacation? =
        prefs.getString("vacation_$eventId", null)?.let { text ->
            val json = JSONObject(text)
            Vacation(json.optString("album"), json.optString("account").ifBlank { null }, json.optBoolean("auto"))
        }

    fun setVacation(eventId: Long, vacation: Vacation?) {
        val edit = prefs.edit()
        if (vacation == null) {
            edit.remove("vacation_$eventId")
        } else {
            val json = JSONObject().put("album", vacation.album).put("account", vacation.account.orEmpty()).put("auto", vacation.autoSend)
            edit.putString("vacation_$eventId", json.toString())
        }
        edit.apply()
    }

    val hasVacations: Boolean
        get() = prefs.all.keys.any { it.startsWith("vacation_") }

    // The menu opened by itself once to show why Google calendars do not come
    var syncHelpShown: Boolean
        get() = prefs.getBoolean("sync_help_shown", false)
        set(value) = prefs.edit().putBoolean("sync_help_shown", value).apply()

    // Google accounts the user picked for this app; the phone itself does not list its accounts to apps
    var googleAccounts: Set<String>
        get() = prefs.getStringSet("google_accounts", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("google_accounts", value).apply()

    // Photos from the Galerie app as the background of the days in the month view
    var showPhotos: Boolean
        get() = prefs.getBoolean("show_photos", true)
        set(value) = prefs.edit().putBoolean("show_photos", value).apply()
    // Events made in this app are mine even when the calendar becomes their organizer
    fun markCreated(eventId: Long) = putLongSet("created_events", longSet("created_events") + eventId)

    fun isCreated(eventId: Long): Boolean = eventId in longSet("created_events")

    // Work calendars then show only the events that concern me, personal ones stay whole
    var onlyMine: Boolean
        get() = prefs.getBoolean("only_mine", false)
        set(value) = prefs.edit().putBoolean("only_mine", value).apply()

    var notifierStarted: Boolean
        get() = prefs.getBoolean("notifier_started", false)
        set(value) = prefs.edit().putBoolean("notifier_started", value).apply()

    // Reminders already shown, stored as "eventId:instance:time"
    var firedReminders: Set<String>
        get() = prefs.getStringSet("fired_reminders", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("fired_reminders", value).apply()

    // ---- Stable keys, so settings find their calendars and events on a new phone ----

    val storedEventIds: Set<Long>
        get() = idsWith(EVENT_KEYS) + longSet("created_events")

    val storedCalendarIds: Set<Long>
        get() = idsWith(listOf("category_")) + hiddenCalendars + listOfNotNull(lastCalendar.takeIf { it >= 0 })

    fun eventRef(eventId: Long): String? = prefs.getString("ref_event_$eventId", null)

    fun setEventRef(eventId: Long, key: String?) = putOrRemove("ref_event_$eventId", key)

    fun calendarRef(calendarId: Long): String? = prefs.getString("ref_calendar_$calendarId", null)

    fun setCalendarRef(calendarId: Long, key: String) = putOrRemove("ref_calendar_$calendarId", key)

    // Moves everything stored under the old ids to the new ones, in one go so swaps work
    fun moveEvents(moves: Map<Long, Long>) {
        moveKeys(moves, EVENT_KEYS + "ref_event_")
        if (moves.isNotEmpty()) putLongSet("created_events", longSet("created_events").map { moves[it] ?: it }.toSet())
    }

    fun moveCalendars(moves: Map<Long, Long>) {
        moveKeys(moves, listOf("category_", "ref_calendar_"))
        hiddenCalendars = hiddenCalendars.map { moves[it] ?: it }.toSet()
        moves[lastCalendar]?.let { lastCalendar = it }
    }

    private fun moveKeys(moves: Map<Long, Long>, prefixes: List<String>) {
        if (moves.isEmpty()) return
        val all = prefs.all
        val edit = prefs.edit()
        val moved = prefixes.flatMap { prefix ->
            moves.mapNotNull { (from, to) -> all["$prefix$from"]?.let { Triple(prefix, to, it) }?.also { edit.remove("$prefix$from") } }
        }
        moved.forEach { (prefix, to, value) -> edit.putString("$prefix$to", value as String) }
        edit.commit()
    }

    private fun idsWith(prefixes: List<String>) =
        prefs.all.keys.mapNotNull { key -> prefixes.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it).toLongOrNull() } }.toSet()

    private fun putOrRemove(key: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(key) else edit.putString(key, value)
        edit.apply()
    }

    fun exportBackup(): String = Backup.export(prefs)

    fun restoreBackup(json: String) = Backup.restore(json, prefs)

    private fun longSet(key: String) = prefs.getStringSet(key, emptySet()).orEmpty().map { it.toLong() }.toSet()

    private fun putLongSet(key: String, value: Set<Long>) =
        prefs.edit().putStringSet(key, value.map { it.toString() }.toSet()).apply()
}

private val EVENT_KEYS = listOf("priority_", "watch_", "vacation_")

fun splitList(text: String): List<String> =
    text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
