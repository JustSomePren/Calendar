package cz.calendar

import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column

private const val GOOGLE_ACCOUNT = "com.google"

// Left menu: calendars of each account to show or hide, adding accounts, settings
@Composable
fun CalendarDrawer(
    calendars: List<CalendarInfo>,
    hidden: Set<Long>,
    onToggle: (Long) -> Unit,
    onOpen: (Intent) -> Unit,
) {
    val context = LocalContext.current
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(
                "Kalendáře",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )

            calendars.groupBy { it.account }.forEach { (account, accountCalendars) ->
                Text(
                    account,
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                accountCalendars.forEach { calendar ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(calendar.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = calendar.id !in hidden, onCheckedChange = { onToggle(calendar.id) })
                        Box(Modifier.size(12.dp).background(Color(calendar.color), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(calendar.name)
                    }
                }
            }
            if (calendars.isEmpty()) {
                Text(
                    "V telefonu zatím není žádný kalendář. Přidej účet Google.",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            NavigationDrawerItem(
                label = { Text("Přidat účet") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                selected = false,
                onClick = {
                    onOpen(Intent(SystemSettings.ACTION_ADD_ACCOUNT).putExtra(SystemSettings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT)))
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            // Calendars of an account appear only when calendar sync is on for it
            NavigationDrawerItem(
                label = { Text("Účty a synchronizace") },
                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                selected = false,
                onClick = { onOpen(Intent(SystemSettings.ACTION_SYNC_SETTINGS)) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text("Nastavení") },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = false,
                onClick = { onOpen(Intent(context, SettingsActivity::class.java)) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
