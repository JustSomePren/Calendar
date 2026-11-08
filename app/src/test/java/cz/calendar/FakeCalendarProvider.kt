package cz.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances

// In-memory calendar provider, just enough for the app's queries
class FakeCalendarProvider : ContentProvider() {

    companion object {
        val calendars = mutableListOf<MutableMap<String, Any?>>()
        val events = mutableListOf<MutableMap<String, Any?>>()
        val attendees = mutableListOf<MutableMap<String, Any?>>()
        private var nextId = 100L

        fun reset() {
            calendars.clear()
            events.clear()
            attendees.clear()
            nextId = 100L
        }

        fun addCalendar(
            id: Long,
            name: String,
            writable: Boolean = true,
            color: Int = 0xFF039BE5.toInt(),
            account: String = "ja@gmail.com",
            // Differs from the account for a colleague's calendar shared with me
            owner: String = account,
            synced: Boolean = true,
        ) {
            calendars += mutableMapOf(
                Calendars._ID to id,
                Calendars.CALENDAR_DISPLAY_NAME to name,
                Calendars.ACCOUNT_NAME to account,
                Calendars.OWNER_ACCOUNT to owner,
                Calendars.CALENDAR_COLOR to color,
                Calendars.CALENDAR_ACCESS_LEVEL to if (writable) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ,
                Calendars.IS_PRIMARY to if (id == 1L) 1 else 0,
                Calendars.SYNC_EVENTS to if (synced) 1 else 0,
                Calendars.ACCOUNT_TYPE to "com.google",
            )
        }

        fun addEvent(values: Map<String, Any?>): Long {
            val id = nextId++
            events += (values + (Events._ID to id)).toMutableMap()
            return id
        }

        fun event(id: Long) = events.first { it[Events._ID] == id }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor {
        val rows: List<Map<String, Any?>> = when (uri.pathSegments.first()) {
            "calendars" -> calendars
            "instances" -> instances(uri.pathSegments[2].toLong(), uri.pathSegments[3].toLong())
            "events" -> when {
                uri.pathSegments.size > 1 -> events.filter { it[Events._ID] == uri.lastPathSegment!!.toLong() }
                selection.orEmpty().startsWith(Events.ORIGINAL_ID) -> events.filter {
                    it[Events.ORIGINAL_ID].toString() == args!![0] && (args.size == 1 || it[Events.ORIGINAL_INSTANCE_TIME].toString() == args[1])
                }
                selection.orEmpty().startsWith(Events.CALENDAR_ID) && args?.size == 1 -> events.filter {
                    it[Events.CALENDAR_ID].toString() == args[0] && it[Events.DELETED] != 1
                }
                args != null -> events.filter {
                    it[Events.CALENDAR_ID].toString() == args[0] && it[Events.TITLE] == args[1] && it[Events.DTSTART].toString() == args[2]
                }
                else -> events
            }
            "attendees" -> attendees.filter { it[Attendees.EVENT_ID].toString() in args.orEmpty() }
            else -> emptyList()
        }
        val cursor = MatrixCursor(projection)
        rows.forEach { row -> cursor.addRow(projection!!.map { row[it] }.toTypedArray()) }
        return cursor
    }

    // No real recurrence expansion, a recurring event shows once at its start
    private fun instances(from: Long, to: Long) = events
        .filter { it[Events.STATUS] != Events.STATUS_CANCELED && it[Events.DELETED] != 1 }
        .map { event ->
            val begin = event[Events.DTSTART] as Long
            val end = (event[Events.DTEND] as Long?) ?: (begin + parseDuration(event[Events.DURATION] as String?).toMillis())
            val calendar = calendars.first { it[Calendars._ID] == event[Events.CALENDAR_ID] }
            event + mapOf(
                Instances.OWNER_ACCOUNT to calendar[Calendars.OWNER_ACCOUNT],
                Instances.EVENT_ID to event[Events._ID],
                Instances.BEGIN to begin,
                Instances.END to end,
                Instances.DISPLAY_COLOR to calendar[Calendars.CALENDAR_COLOR],
            )
        }
        .filter { (it[Instances.BEGIN] as Long) < to && (it[Instances.END] as Long) > from }
        .sortedBy { it[Instances.BEGIN] as Long }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val map = values!!.keySet().associateWith { values.get(it) }.toMutableMap()
        return when (uri.pathSegments.first()) {
            "events" -> ContentUris.withAppendedId(Events.CONTENT_URI, addEvent(map))
            "calendars" -> {
                val id = (calendars.maxOfOrNull { it[Calendars._ID] as Long } ?: 0L) + 1
                calendars += (map + (Calendars._ID to id)).toMutableMap()
                ContentUris.withAppendedId(Calendars.CONTENT_URI, id)
            }
            "exception" -> {
                val original = event(uri.lastPathSegment!!.toLong())
                val copy = original - Events._ID - Events.RRULE - Events.DURATION + map +
                    (Events.ORIGINAL_ID to original[Events._ID])
                ContentUris.withAppendedId(Events.CONTENT_URI, addEvent(copy))
            }
            "attendees" -> {
                attendees += map
                ContentUris.withAppendedId(Attendees.CONTENT_URI, attendees.size.toLong())
            }
            else -> error("Unexpected insert $uri")
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        if (uri.pathSegments.first() == "calendars") {
            val calendar = calendars.first { it[Calendars._ID] == uri.lastPathSegment!!.toLong() }
            values!!.keySet().forEach { calendar[it] = values.get(it) }
            return 1
        }
        val event = event(uri.lastPathSegment!!.toLong())
        values!!.keySet().forEach { event[it] = values.get(it) }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = when (uri.pathSegments.first()) {
        "events" -> when {
            uri.pathSegments.size > 1 -> if (events.removeIf { it[Events._ID] == uri.lastPathSegment!!.toLong() }) 1 else 0
            selection.orEmpty().startsWith(Events.ORIGINAL_ID) -> events.count { it[Events.ORIGINAL_ID].toString() == args!![0] }
                .also { events.removeIf { it[Events.ORIGINAL_ID].toString() == args!![0] } }
            else -> 0
        }
        "attendees" -> if (
            attendees.removeIf {
                it[Attendees.EVENT_ID].toString() == args!![0] && it[Attendees.ATTENDEE_EMAIL].toString().lowercase() == args[1]
            }
        ) 1 else 0
        else -> 0
    }
}
