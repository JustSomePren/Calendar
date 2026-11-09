package cz.calendar

import android.content.Context

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

    private fun longSet(key: String) = prefs.getStringSet(key, emptySet()).orEmpty().map { it.toLong() }.toSet()

    private fun putLongSet(key: String, value: Set<Long>) =
        prefs.edit().putStringSet(key, value.map { it.toString() }.toSet()).apply()
}

fun splitList(text: String): List<String> =
    text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
