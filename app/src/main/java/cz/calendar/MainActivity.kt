package cz.calendar

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    // Bumped whenever calendar data may have changed, screens reload on it
    private val refreshTick = mutableIntStateOf(0)

    // Day tapped in the widget, shown once the month screen is ready
    private val openDay = mutableStateOf<LocalDate?>(null)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshTick.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a rotation the day from the widget was already shown
        if (savedInstanceState == null) openDay.value = intent.dayExtra()

        val repository = CalendarRepository(contentResolver)
        val settings = Settings(this)
        setContent {
            CalendarTheme {
                CalendarApp(repository, settings, refreshTick.intValue, openDay.value) { openDay.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openDay.value = intent.dayExtra()
    }

    // Resumed also after the permission dialog, so access granted there is picked up at once.
    // Watching the calendar without access would crash, so wait for it.
    override fun onResume() {
        super.onResume()
        if (hasCalendarAccess()) contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        BackgroundWork.start(this)
        refreshTick.intValue++
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(observer)
        super.onPause()
    }
}

@Composable
fun CalendarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
