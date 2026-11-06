package cz.calendar

import android.content.Context

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var hiddenCalendars: Set<Long>
        get() = prefs.getStringSet("hidden_calendars", emptySet()).orEmpty().map { it.toLong() }.toSet()
        set(value) = prefs.edit().putStringSet("hidden_calendars", value.map { it.toString() }.toSet()).apply()
}
