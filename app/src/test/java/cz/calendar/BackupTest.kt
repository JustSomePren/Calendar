package cz.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupTest {

    @Test
    fun everySettingComesBack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = Settings(app)
        settings.hiddenCalendars = setOf(3L, 4L)
        settings.lastCalendar = 2
        settings.setCategory(2, Category.WORK)
        settings.workKeywords = listOf("testProjekt")
        settings.setPriority(10, Priority.HIGH)
        settings.setWatch(11, Watch.NEVER)
        settings.setVacation(12, Vacation("Moře", "ja@gmail.com", autoSend = true))
        val backup = settings.exportBackup()

        app.getSharedPreferences("settings", 0).edit().clear().commit()
        assertEquals(Category.PERSONAL, settings.category(2))
        settings.restoreBackup(backup)

        assertEquals(setOf(3L, 4L), settings.hiddenCalendars)
        assertEquals(2L, settings.lastCalendar)
        assertEquals(Category.WORK, settings.category(2))
        assertEquals(listOf("testProjekt"), settings.workKeywords)
        assertEquals(Priority.HIGH, settings.priority(10))
        assertEquals(Watch.NEVER, settings.watch(11))
        assertEquals(Vacation("Moře", "ja@gmail.com", autoSend = true), settings.vacation(12))
    }
}
