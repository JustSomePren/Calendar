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
        openDay.value = intent.dayExtra()
        BackgroundWork.start(this)
        kotlin.concurrent.thread { repairIds(this) }

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

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        refreshTick.intValue++
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        super.onStop()
    }
}

@Composable
fun CalendarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
