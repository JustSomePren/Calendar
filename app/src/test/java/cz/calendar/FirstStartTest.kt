package cz.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
class FirstStartTest {

    // A real phone closes the app when it watches the calendar without access
    @Test
    fun watchesTheCalendarOnlyWithAccess() {
        val app = setUpFakeCalendar()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val observers = { Shadows.shadowOf(app.contentResolver).getContentObservers(CalendarContract.CONTENT_URI).size }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(0, observers())

            Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(1, observers())
        }
    }
}
