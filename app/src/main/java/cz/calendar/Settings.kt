package cz.calendar

import android.content.Context

enum class Category(val label: String, val hint: String) {
    PERSONAL("Osobní", "Upozorní na všechny události"),
    WORK("Pracovní", "Upozorní jen na události podle pracovního filtru"),
    QUIET("Tichý", "Jen zobrazit, nikdy neupozorňovat"),
}

enum class Priority(val label: String) {
    NONE("Bez upozornění"),
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

    fun eventsWithPriority(priority: Priority): Set<Long> =
        prefs.all.filter { (key, value) -> key.startsWith("priority_") && value == priority.name }
            .keys.map { it.removePrefix("priority_").toLong() }.toSet()

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
