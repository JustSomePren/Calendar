package cz.calendar

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.robolectric.Robolectric
import org.robolectric.Shadows

fun setUpFakeCalendar(): Application {
    FakeCalendarProvider.reset()
    Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
    val app = ApplicationProvider.getApplicationContext<Application>()
    Shadows.shadowOf(app).grantPermissions(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    // WorkManager starts itself on a real phone, Robolectric needs a hand
    runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
    return app
}
