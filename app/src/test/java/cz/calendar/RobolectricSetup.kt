package cz.calendar

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
        // Declared by AndroidX for receivers on older Android, granted on install on a phone
        "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )
    // WorkManager starts itself on a real phone. Here a fresh test one per test, so background work
    // left over from an earlier test cannot run into the next one.
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    // Robolectric has no Google Calendar app; the menu must not open by itself over every test
    Settings(app).syncHelpShown = true
    return app
}
