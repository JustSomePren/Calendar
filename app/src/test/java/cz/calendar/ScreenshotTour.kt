package cz.calendar

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

// Pictures of every screen with sample data, saved to app/build/shots for a visual check
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTour {

    private lateinit var app: Application
    private var dentist = 0L
    private var standup = 0L
    private var holiday = 0L
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        app = setUpFakeCalendar()
        FakeCalendarProvider.addCalendar(1, "Osobní")
        FakeCalendarProvider.addCalendar(2, "Práce", color = 0xFF33B679.toInt(), owner = "ja@firma.cz")
        FakeCalendarProvider.addCalendar(3, "Státní svátky", writable = false, color = 0xFF0B8043.toInt())

        val first = today.withDayOfMonth(1)
        dentist = timed(1, "Zubař", today, 9, mapOf(Events.EVENT_LOCATION to "Vinohradská 12, Praha"))
        timed(2, "Porada týmu", today, 13, mapOf(Events.ORGANIZER to "sef@firma.cz"))
        standup = timed(2, "Standup", first, 8, mapOf(Events.RRULE to "FREQ=DAILY", Events.DTEND to null, Events.DURATION to "P900S"))
        holiday = allDay(3, "Den boje za svobodu a demokracii", first.plusDays(16), 1)
        allDay(1, "Dovolená Alpy", first.plusDays(19), 6)
        allDay(2, "Konference", first.plusDays(21), 2)
        for (week in 0 until 5) timed(1, "Fotbal", first.plusDays(1L + week * 7), 18)
        for (i in 0 until 6) timed(1, "Schůzka $i", first.plusDays(13), 8 + i)
        FakeCalendarProvider.attendees += mutableMapOf(
            Attendees.EVENT_ID to dentist,
            Attendees.ATTENDEE_EMAIL to "jana@gmail.com",
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED,
        )

        Settings(app).apply {
            setPriority(dentist, Priority.HIGH)
            setCategory(2, Category.WORK)
            setCategory(3, Category.QUIET)
            workEmails = listOf("ja@firma.cz")
            workKeywords = listOf("testProjekt")
        }
    }

    private fun millis(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun timed(calendar: Long, title: String, day: LocalDate, hour: Int, extra: Map<String, Any?> = emptyMap()) =
        FakeCalendarProvider.addEvent(
            mapOf(
                Events.CALENDAR_ID to calendar,
                Events.TITLE to title,
                Events.ALL_DAY to 0,
                Events.DTSTART to millis(day, hour),
                Events.DTEND to millis(day, hour + 1),
            ) + extra
        )

    private fun allDay(calendar: Long, title: String, day: LocalDate, days: Long) = FakeCalendarProvider.addEvent(
        mapOf(
            Events.CALENDAR_ID to calendar,
            Events.TITLE to title,
            Events.ALL_DAY to 1,
            Events.DTSTART to day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            Events.DTEND to day.plusDays(days).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
    )

    // Background loading runs on other threads, so give it real time
    private fun settle() = repeat(60) {
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        Thread.sleep(50)
    }

    private inline fun <reified T : Activity> shot(name: String, intent: Intent = Intent(app, T::class.java), wholeScreen: Boolean = false) {
        ActivityScenario.launch<T>(intent).use { scenario ->
            settle()
            scenario.onActivity {
                // The whole screen includes dialogs and bottom sheets on top of the activity
                if (wholeScreen) captureScreenRoboImage("build/shots/$name.png") else it.window.decorView.captureRoboImage("build/shots/$name.png")
            }
        }
    }

    private fun editor(eventId: Long? = null, instanceBegin: Long? = null) = Intent(app, EventEditActivity::class.java).apply {
        eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
        instanceBegin?.let { putExtra(EXTRA_INSTANCE_BEGIN, it) }
        putExtra(EXTRA_DAY, today.toString())
    }

    @Test
    fun month() = shot<MainActivity>("01_month")

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    fun monthDark() = shot<MainActivity>("02_month_dark")

    @Test
    fun dayPanel() = shot<MainActivity>(
        "03_day_panel",
        Intent(app, MainActivity::class.java).putExtra(EXTRA_DAY, today.toString()),
        wholeScreen = true,
    )

    @Test
    fun newEvent() = shot<EventEditActivity>("04_editor_new", editor())

    @Test
    fun existingEvent() = shot<EventEditActivity>("05_editor_existing", editor(dentist))

    @Test
    fun recurringOccurrence() = shot<EventEditActivity>("06_editor_recurring", editor(standup, millis(today.withDayOfMonth(1), 8)))

    @Test
    fun readOnlyEvent() = shot<EventEditActivity>("07_editor_read_only", editor(holiday))

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun settings() = shot<SettingsActivity>("08_settings")

    @Test
    fun noPermission() {
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR)
        shot<MainActivity>("09_no_permission")
    }

    @Test
    fun widget() {
        val width = 360
        val height = 420
        val scale = 3f
        val views = runBlocking { MonthWidget().compose(app, size = DpSize(width.dp, height.dp)) }
        val parent = FrameLayout(app)
        val w = (width * scale).toInt()
        val h = (height * scale).toInt()
        parent.addView(views.apply(app, parent), FrameLayout.LayoutParams(w, h))
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            // Wallpaper stand-in, rounded corners are not drawn off the phone
            drawColor(0xFF607D8B.toInt())
            parent.draw(this)
        }
        File("build/shots/10_widget.png").apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
