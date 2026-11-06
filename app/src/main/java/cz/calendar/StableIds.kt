package cz.calendar

import android.content.Context

// Android numbers calendars and events again on every phone. Each setting also remembers
// a stable key, so after a backup is restored on a new phone it moves to the right event.
fun repairIds(context: Context) {
    val settings = Settings(context)
    val repository = CalendarRepository(context.contentResolver)
    runCatching {
        val calendars = repository.calendarKeys()
        val calendarMoves = matchIds(settings.storedCalendarIds, settings::calendarRef, settings::setCalendarRef, { id ->
            calendars[id]?.let(::listOf)
        }) { ref -> calendars.entries.firstOrNull { it.value == ref }?.key }
        settings.moveCalendars(calendarMoves)

        val ids = settings.storedEventIds
        if (ids.isEmpty()) return
        val current = repository.eventKeys(calendars, ids)
        // Reading all events is slower, so only when some event has moved
        val all by lazy { repository.eventKeys(calendars) }
        val eventMoves = matchIds(ids, settings::eventRef, settings::setEventRef, current::get) { ref ->
            all.entries.firstOrNull { ref in it.value }?.key
        }
        settings.moveEvents(eventMoves)
    }
}

// Old id to new id for settings whose id now belongs to something else. Unknown keys wait,
// e.g. until a new phone finishes syncing.
private fun matchIds(
    ids: Set<Long>,
    ref: (Long) -> String?,
    setRef: (Long, String) -> Unit,
    keysOf: (Long) -> List<String>?,
    find: (String) -> Long?,
): Map<Long, Long> {
    val moves = mutableMapOf<Long, Long>()
    for (id in ids) {
        val stored = ref(id)
        val keys = keysOf(id)
        if (keys != null && (stored == null || stored in keys)) {
            if (stored != keys.first()) setRef(id, keys.first())
        } else if (stored != null) {
            find(stored)?.takeIf { it != id }?.let { moves[id] = it }
        }
    }
    return moves
}
