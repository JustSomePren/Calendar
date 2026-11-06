package cz.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class EventEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1).takeIf { it >= 0 }
        val instanceBegin = intent.getLongExtra(EXTRA_INSTANCE_BEGIN, -1).takeIf { it >= 0 }
        val day = intent.dayExtra() ?: LocalDate.now()

        setContent {
            CalendarTheme {
                EventEditScreen(
                    repository = CalendarRepository(contentResolver),
                    settings = Settings(this),
                    eventId = eventId,
                    instanceBegin = instanceBegin,
                    day = day,
                    onClose = ::finish,
                )
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEE d. M. yyyy", czech)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun defaultCalendar(calendars: List<CalendarInfo>, lastUsed: Long): CalendarInfo? {
    val writable = calendars.filter { it.writable }
    return writable.find { it.id == lastUsed } ?: writable.find { it.primary } ?: writable.firstOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditScreen(
    repository: CalendarRepository,
    settings: Settings,
    eventId: Long?,
    instanceBegin: Long?,
    day: LocalDate,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var original by remember { mutableStateOf<EventDraft?>(null) }
    var draft by remember { mutableStateOf<EventDraft?>(null) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (allCalendars, loaded) = withContext(Dispatchers.IO) {
            val all = repository.calendars()
            val event = if (eventId != null) {
                repository.event(eventId)
            } else {
                defaultCalendar(all, settings.lastCalendar)?.let { EventDraft.new(it.id, day) }
            }
            all to event
        }
        if (eventId != null && loaded == null) {
            onClose()
            return@LaunchedEffect
        }
        calendars = allCalendars
        original = loaded
        draft = loaded
        loading = false
    }

    // Runs a change on the calendar provider, then closes the editor
    fun commit(action: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            if (result.isSuccess) {
                onClose()
            } else {
                Toast.makeText(context, "Změnu se nepodařilo uložit", Toast.LENGTH_LONG).show()
            }
        }
    }

    val current = draft
    val calendar = calendars.find { it.id == current?.calendarId }
    val editable = calendar?.writable == true
    val recurring = original?.rule != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (eventId == null) "Nová událost" else "Událost") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Zavřít") }
                },
                actions = {
                    if (eventId != null && editable) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Smazat")
                        }
                    }
                    if (current != null && editable) {
                        TextButton(
                            enabled = current.isValid,
                            onClick = {
                                val updateReminder = current.id == null || current.reminder != original?.reminder
                                settings.lastCalendar = current.calendarId
                                commit { repository.saveEvent(current, updateReminder) }
                            },
                        ) { Text("Uložit") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                current == null -> Text(
                    "V telefonu není žádný kalendář, do kterého jde zapisovat.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> EventForm(
                    draft = current,
                    calendars = calendars.filter { it.writable || it.id == current.calendarId },
                    originalRule = original?.rule,
                    editable = editable,
                    canChangeCalendar = current.id == null,
                    recurring = recurring,
                    onChange = { draft = it },
                )
            }
        }
    }

    if (showDelete && current?.id != null) {
        val id = current.id
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Smazat událost?") },
            text = if (recurring) {
                { Text("Jde o opakovanou událost.") }
            } else {
                null
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (recurring && instanceBegin != null) {
                        TextButton(onClick = { commit { repository.deleteOccurrence(id, instanceBegin) } }) {
                            Text("Jen tento výskyt")
                        }
                    }
                    TextButton(onClick = { commit { repository.deleteEvent(id) } }) {
                        Text(if (recurring) "Celou sérii" else "Smazat")
                    }
                    TextButton(onClick = { showDelete = false }) { Text("Zrušit") }
                }
            },
        )
    }
}

@Composable
private fun EventForm(
    draft: EventDraft,
    calendars: List<CalendarInfo>,
    originalRule: String?,
    editable: Boolean,
    canChangeCalendar: Boolean,
    recurring: Boolean,
    onChange: (EventDraft) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!editable) {
            Notice("Tento kalendář je jen pro čtení.")
        } else if (recurring && draft.id != null) {
            Notice("Změny se projeví u všech opakování. Začátek je datum prvního výskytu.")
        }

        OutlinedTextField(
            value = draft.title,
            onValueChange = { onChange(draft.copy(title = it)) },
            label = { Text("Název") },
            enabled = editable,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Celý den", Modifier.weight(1f))
            Switch(draft.allDay, onCheckedChange = { onChange(draft.copy(allDay = it)) }, enabled = editable)
        }

        DateTimeRow("Začátek", draft.start, draft.allDay, editable) { start ->
            // Moving the start keeps the event length, like most calendars do
            onChange(draft.copy(start = start, end = draft.end.plus(Duration.between(draft.start, start))))
        }
        DateTimeRow("Konec", draft.end, draft.allDay, editable) { onChange(draft.copy(end = it)) }
        if (!draft.isValid) {
            Text("Konec je dřív než začátek.", color = MaterialTheme.colorScheme.error)
        }

        ChoiceRow(
            label = "Kalendář",
            options = calendars.map { it.id to it.name },
            selected = draft.calendarId,
            enabled = editable && canChangeCalendar,
        ) { onChange(draft.copy(calendarId = it)) }
        ChoiceRow("Opakování", repeatChoices(originalRule), draft.rule, editable) { onChange(draft.copy(rule = it)) }
        ChoiceRow("Upozornění", reminderChoices(draft.reminder), draft.reminder, editable) {
            onChange(draft.copy(reminder = it))
        }

        OutlinedTextField(
            value = draft.location,
            onValueChange = { onChange(draft.copy(location = it)) },
            label = { Text("Místo") },
            enabled = editable,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.description,
            onValueChange = { onChange(draft.copy(description = it)) },
            label = { Text("Poznámka") },
            enabled = editable,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DateTimeRow(
    label: String,
    value: LocalDateTime,
    allDay: Boolean,
    enabled: Boolean,
    onChange: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(enabled = enabled, onClick = { pickDate(context, value.toLocalDate()) { onChange(value.with(it)) } }) {
            Text(value.format(dateFormat))
        }
        if (!allDay) {
            TextButton(enabled = enabled, onClick = { pickTime(context, value.toLocalTime()) { onChange(value.with(it)) } }) {
                Text(value.format(timeFormat))
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(enabled = enabled, onClick = { expanded = true }) {
                Text(options.find { it.first == selected }?.second.orEmpty())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

private fun pickDate(context: Context, date: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth)
        .apply { datePicker.firstDayOfWeek = java.util.Calendar.MONDAY }
        .show()
}

private fun pickTime(context: Context, time: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, hour, minute -> onPick(LocalTime.of(hour, minute)) }, time.hour, time.minute, true).show()
}
