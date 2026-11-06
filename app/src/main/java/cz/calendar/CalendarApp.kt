package cz.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

// Asking for notifications exists only since Android 13
private val notificationPermissions =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

fun Context.hasCalendarAccess(): Boolean =
    calendarPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

fun Context.canNotify(): Boolean =
    notificationPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun CalendarApp(
    repository: CalendarRepository,
    settings: Settings,
    refreshTick: Int,
    openDay: LocalDate?,
    onOpenDayHandled: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCalendarAccess()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = context.hasCalendarAccess()
        BackgroundWork.refreshNow(context)
    }

    LaunchedEffect(Unit) {
        val missing = (calendarPermissions + notificationPermissions)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    Surface(Modifier.fillMaxSize()) {
        if (hasPermission) {
            MonthScreen(repository, settings, refreshTick, openDay, onOpenDayHandled)
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Aplikace potřebuje přístup ke kalendářům v telefonu.", textAlign = TextAlign.Center)
                Button(onClick = { launcher.launch(calendarPermissions) }) {
                    Text("Povolit přístup")
                }
                TextButton(onClick = {
                    val intent = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }) {
                    Text("Otevřít nastavení aplikace")
                }
            }
        }
    }
}
