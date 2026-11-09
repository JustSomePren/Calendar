package cz.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val calendarColors = listOf(0xFF039BE5, 0xFF33B679, 0xFFE67C73, 0xFFF6BF26, 0xFF8E24AA, 0xFF616161).map { it.toInt() }

// Left menu: connected accounts with their calendars to show, hide or start syncing, adding accounts and calendars
@Composable
fun CalendarDrawer(
    calendars: List<CalendarInfo>,
    hidden: Set<Long>,
    onlyMine: Boolean,
    onOnlyMine: (Boolean) -> Unit,
    onToggle: (CalendarInfo) -> Unit,
    onAddAccount: () -> Unit,
    onNewCalendar: () -> Unit,
    onSettings: () -> Unit,
    onSyncNow: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(
                "Připojené účty",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )

            Row(
                Modifier.fillMaxWidth().toggleable(onlyMine, onValueChange = onOnlyMine).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Jen co se mě týká")
                    Hint("Pracovní kalendáře ukážou jen tvoje události, osobní vše")
                }
                Switch(checked = onlyMine, onCheckedChange = null)
            }

            calendars.groupBy { it.account }.forEach { (account, accountCalendars) ->
                Text(
                    account,
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Synced calendars first, the ones still waiting to be switched on below
                accountCalendars.sortedByDescending { it.synced }.forEach { calendar ->
                    CalendarRow(calendar, shown = calendar.synced && calendar.id !in hidden, onToggle = { onToggle(calendar) })
                }
            }
            if (calendars.isEmpty()) {
                Text(
                    "V telefonu zatím není žádný kalendář. Přidej účet, nebo založ kalendář v telefonu.",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            DrawerItem("Přidat účet", Icons.Default.Add, onAddAccount)
            DrawerItem("Synchronizovat teď", Icons.Default.Refresh, onSyncNow)
            DrawerItem("Nový kalendář v telefonu", Icons.Default.DateRange, onNewCalendar)
            DrawerItem("Nastavení", Icons.Default.Settings, onSettings)
        }
    }
}

@Composable
private fun CalendarRow(calendar: CalendarInfo, shown: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = shown, onCheckedChange = { onToggle() })
        Box(Modifier.size(12.dp).background(Color(calendar.color), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(calendar.name)
            if (!calendar.synced) Hint("Nesynchronizuje se – zaškrtnutím zapneš")
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
fun NewCalendarFields(name: String, onName: (String) -> Unit, color: Int, onColor: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, onName, label = { Text("Název") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            calendarColors.forEach { option ->
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Color(option), CircleShape)
                        .border(3.dp, if (option == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .clickable { onColor(option) }
                        .semantics {
                            contentDescription = "Barva"
                            selected = option == color
                        },
                )
            }
        }
        Hint("Kalendář zůstane jen v tomto telefonu, nikam se nesynchronizuje a nezálohuje.")
    }
}

// A calendar kept only in this phone, for use without any account
@Composable
fun NewCalendarDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(calendarColors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nový kalendář v telefonu") },
        text = { NewCalendarFields(name, { name = it }, color, { color = it }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), color) }) { Text("Založit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
