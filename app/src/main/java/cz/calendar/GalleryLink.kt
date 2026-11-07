package cz.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// Talks to the Galerie app, when it is installed. Both sides check who is calling by the signing key.
object GalleryLink {
    const val PACKAGE = "com.justsomepren.gallery"
    private const val AUTHORITY = "com.justsomepren.gallery.link"
    private const val ACTION_SHOW = "com.justsomepren.gallery.action.SHOW_PHOTOS"
    private const val CERT = "8B48624FEFF248DEBB0906A02E0255030B3FE85CE5D2C2420FA653CB2ED81ABE"

    // Photos taken a bit before or after a timed event still belong to it
    private val BEFORE = Duration.ofMinutes(15)
    private val AFTER = Duration.ofMinutes(30)

    fun installed(context: Context) = context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    fun isGallery(context: Context, pkg: String?) =
        pkg == PACKAGE && context.packageManager.hasSigningCertificate(pkg, CERT.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), PackageManager.CERT_INPUT_SHA256)

    fun accounts(context: Context): List<String> = query(context, "accounts") { it.getString(0) }.orEmpty()

    // Ids of the photos taken in the window, oldest first. Null when the gallery did not answer.
    fun photos(context: Context, window: Pair<Long, Long>): List<Long>? =
        query(context, "photos", windowParams(window)) { it.getLong(0) }

    // How many photos each window has, in one call. Null when the gallery did not answer.
    fun counts(context: Context, windows: List<Pair<Long, Long>>): List<Int>? =
        if (windows.isEmpty()) emptyList()
        else query(context, "counts", mapOf("windows" to windows.joinToString(",") { "${it.first}-${it.second}" })) { it.getInt(0) }

    // Where the photos were taken, if they have a GPS position
    fun place(context: Context, window: Pair<Long, Long>): Pair<Double, Double>? =
        query(context, "place", windowParams(window)) { it.getDouble(0) to it.getDouble(1) }?.firstOrNull()

    // Recently shown thumbnails, so paging through months does not ask the gallery again
    private val thumbnails = LruCache<Long, Bitmap>(80)

    fun thumbnail(context: Context, photoId: Long): Bitmap? = thumbnails[photoId] ?: runCatching {
        // Half the memory of full colour, invisible at this size
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        context.contentResolver.openInputStream(uri("thumb/$photoId"))?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()?.also { thumbnails.put(photoId, it) }

    // The best photo of each day, only what was really taken with the camera
    fun covers(context: Context, from: LocalDate, to: LocalDate): Map<LocalDate, Bitmap> =
        query(context, "covers", mapOf("from" to "${from.toEpochDay()}", "to" to "${to.toEpochDay()}")) { c ->
            LocalDate.ofEpochDay(c.getLong(0)) to c.getLong(1)
        }.orEmpty().mapNotNull { (day, id) -> thumbnail(context, id)?.let { day to it } }.toMap()

    fun open(context: Context, title: String, window: Pair<Long, Long>) {
        val intent = Intent(ACTION_SHOW).setPackage(PACKAGE)
            .putExtra("title", title)
            .putExtra("from", window.first)
            .putExtra("to", window.second)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "Galerii se nepodařilo otevřít", Toast.LENGTH_SHORT).show() }
    }

    // The gallery then reads the vacations again. A provider call, so only the gallery receives it.
    fun notifyChanged(context: Context) {
        runCatching { context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "calendar_changed", null, null) }
    }

    // Start and end in millis, end exclusive. Vacations and all-day events take whole days.
    fun window(draft: EventDraft): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        fun millis(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()
        return if (draft.allDay || draft.vacation != null) {
            millis(draft.start.toLocalDate().atStartOfDay()) to millis(draft.end.toLocalDate().plusDays(1).atStartOfDay())
        } else {
            millis(draft.start.minus(BEFORE)) to millis(draft.end.plus(AFTER))
        }
    }

    // The same for an event in the month view, which has no draft
    fun window(event: CalendarEvent, vacation: Boolean): Pair<Long, Long> {
        if (!event.allDay && !vacation) return event.begin - BEFORE.toMillis() to event.end + AFTER.toMillis()
        val zone = ZoneId.systemDefault()
        val days = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else zone)
        return days.start.atStartOfDay(zone).toInstant().toEpochMilli() to
            days.endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun uri(path: String) = Uri.parse("content://$AUTHORITY/$path")

    private fun windowParams(window: Pair<Long, Long>) = mapOf("from" to "${window.first}", "to" to "${window.second}")

    private fun <T> query(context: Context, path: String, params: Map<String, String> = emptyMap(), read: (Cursor) -> T): List<T>? {
        val uri = uri(path).buildUpon().apply { params.forEach { (key, value) -> appendQueryParameter(key, value) } }.build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(read(c)) }
            }
        }.getOrNull()
    }
}

val LINK_COLUMNS = arrayOf(
    "id", "begin", "end", "all_day", "first_day", "last_day", "title", "color", "vacation", "album", "account", "auto_send",
)

// Events from personal calendars, for the gallery to pair photos with
fun linkedEvents(context: Context, from: LocalDate, to: LocalDate): MatrixCursor {
    val settings = Settings(context)
    val cursor = MatrixCursor(LINK_COLUMNS)
    CalendarRepository(context.contentResolver).eventsByDay(from, to, settings.hiddenCalendars).values.flatten()
        .distinctBy { it.eventId to it.begin }
        .filter { settings.category(it.calendarId) == Category.PERSONAL }
        .forEach { event ->
            val days = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else ZoneId.systemDefault())
            val vacation = settings.vacation(event.eventId)
            cursor.addRow(
                arrayOf<Any?>(
                    event.eventId, event.begin, event.end, if (event.allDay) 1 else 0,
                    days.start.toEpochDay(), days.endInclusive.toEpochDay(), event.title, event.color,
                    if (vacation != null) 1 else 0, vacation?.album?.ifBlank { event.title }, vacation?.account,
                    if (vacation?.autoSend == true) 1 else 0,
                ),
            )
        }
    return cursor
}

class LinkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val context = context ?: return null
        if (!GalleryLink.isGallery(context, callingPackage)) return null
        val from = uri.getQueryParameter("from")?.toLongOrNull() ?: return null
        val to = uri.getQueryParameter("to")?.toLongOrNull() ?: return null
        // No calendar permission gives null, so the gallery keeps what it has
        return runCatching { linkedEvents(context, LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(to)) }.getOrNull()
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
