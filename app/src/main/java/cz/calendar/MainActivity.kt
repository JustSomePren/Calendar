package cz.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // Bumped whenever calendar data may have changed, screens reload on it
    private val refreshTick = mutableIntStateOf(0)
    private var hadAccess = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshTick.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = CalendarRepository(contentResolver)
        val settings = Settings(this)
        setContent {
            CalendarTheme {
                CalendarApp(repository, settings, refreshTick.intValue)
            }
        }
    }

    // Resumed also after the permission dialog, so access granted there is picked up at once.
    // Watching the calendar without access would crash, so wait for it.
    override fun onResume() {
        super.onResume()
        val access = hasCalendarAccess()
        if (access) contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        // Access that just appeared (e.g. granted in system settings) needs reminders and widget planned now
        BackgroundWork.start(this, refreshWidget = access && !hadAccess)
        val clock = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // System clock broadcasts still arrive at a not exported receiver
        ContextCompat.registerReceiver(this, clockChanged, clock, ContextCompat.RECEIVER_NOT_EXPORTED)
        hadAccess = access
        refreshTick.intValue++
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(observer)
        unregisterReceiver(clockChanged)
        super.onPause()
    }

    // Midnight or a clock change while the app is on screen moves "today"
    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshTick.intValue++
        }
    }
}

@Composable
fun CalendarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Colours from the wallpaper exist since Android 12, older phones get fixed ones
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
