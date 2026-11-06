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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
private val priorityOptions = Priority.entries.map { it to it.label }
private val watchOptions = Watch.entries.map { it to it.label }

private fun defaultCalendar(calendars: List<CalendarInfo>, lastUsed: Long): CalendarInfo? {
    val writable = calendars.filter { it.writable }
    return writable.find { it.id == lastUsed } ?: writable.find { it.primary } ?: writable.firstOrNull()
}

private data class Loaded(val calendars: List<CalendarInfo>, val draft: EventDraft?, val attendees: List<Attendee>)

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
    var statuses by remember { mutableStateOf(emptyList<Attendee>()) }
    // Saved with the screen, so unsaved edits survive rotating the phone
    var original by rememberSaveable { mutableStateOf<EventDraft?>(null) }
    var draft by rememberSaveable { mutableStateOf<EventDraft?>(null) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showSaveChoice by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val all = repository.calendars()
            val event = if (eventId != null) {
                repository.event(eventId, instanceBegin)?.let { event ->
                    val series = event.originalId ?: eventId
                    event.copy(priority = settings.priorityFor(eventId, series), watch = settings.watchFor(eventId, series))
                }
            } else {
                defaultCalendar(all, settings.lastCalendar)?.let { EventDraft.new(it.id, day) }
            }
            Loaded(all, event, eventId?.let { repository.attendees(it) }.orEmpty())
        }
        if (eventId != null && loaded.draft == null) {
            onClose()
            return@LaunchedEffect
        }
        calendars = loaded.calendars
        statuses = loaded.attendees
        // After a rotation the restored draft holds the user's edits
        if (draft == null) {
            original = loaded.draft
            draft = loaded.draft
        }
        loading = false
    }

    // Runs a change on the calendar provider, then closes the editor
    // Reminder choices stay with the opened event, for an occurrence that is its series
    fun commit(saved: EventDraft? = null, action: () -> Long?) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            if (result.isSuccess) {
                val savedId = saved?.let { it.originalId ?: it.id } ?: result.getOrNull()
                if (savedId != null && saved != null) {
                    if (saved.id == null) settings.markCreated(savedId)
                    settings.setPriority(savedId, saved.priority)
                    settings.setWatch(savedId, saved.watch)
                }
                BackgroundWork.refreshNow(context)
                onClose()
            } else {
                Toast.makeText(context, "Změnu se nepodařilo uložit", Toast.LENGTH_LONG).show()
            }
        }
    }

    val current = draft
    val calendar = calendars.find { it.id == current?.calendarId }
    val editable = calendar?.writable == true
    // An occurrence changed on its own has no rule but still belongs to a series
    val changedOccurrence = original?.originalId != null
    val recurring = original?.rule != null || changedOccurrence
    val occurrence = recurring && current?.instanceBegin != null
    val onlyLocalChanges = current != null && current.id != null &&
        current.copy(priority = Priority.NORMAL, watch = Watch.AUTO) == original?.copy(priority = Priority.NORMAL, watch = Watch.AUTO)
    val autoHint = current?.let { event ->
        val upcoming = event.toUpcoming(calendar?.account.orEmpty())
        val people = event.attendees.map { email -> statuses.find { it.email.equals(email, ignoreCase = true) } ?: Attendee(email, 0) }
        val matches = matchesWorkFilter(upcoming, people, settings.workEmails, settings.workKeywords)
        val mine = upcoming.isMine || settings.isCreated(event.originalId ?: event.id ?: -1)
        autoReminderHint(settings.category(event.calendarId), mine, matches)
    }.orEmpty()

    fun saveSeries(event: EventDraft) {
        if (event.rule != null && shiftRule(event.rule, event.seriesDayShift()) == null) {
            Toast.makeText(context, "Tahle série se opakuje podle data, celou ji na jiný den přesunout nejde. Přesuň jen tento výskyt.", Toast.LENGTH_LONG).show()
            return
        }
        settings.lastCalendar = event.calendarId
        commit(event) { repository.saveEvent(event) }
    }

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
                    if (current != null) {
                        TextButton(
                            enabled = current.isValid,
                            onClick = {
                                when {
                                    // Only reminder choices changed, nothing to write to the calendar
                                    !editable || onlyLocalChanges -> commit(current) { current.id }
                                    occurrence -> showSaveChoice = true
                                    else -> saveSeries(current)
                                }
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
                    statuses = statuses,
                    originalRule = original?.rule,
                    editable = editable,
                    canChangeCalendar = current.id == null,
                    recurring = recurring,
                    changedOccurrence = changedOccurrence,
                    autoHint = autoHint,
                    onChange = { draft = it },
                )
            }
        }
    }

    if (showSaveChoice && current != null) {
        AlertDialog(
            onDismissRequest = { showSaveChoice = false },
            title = { Text("Uložit opakovanou událost") },
            text = { Text("Účastníci a opakování se mění jen u celé série.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { commit(current) { repository.saveOccurrence(current) } }) {
                        Text("Jen tento výskyt")
                    }
                    TextButton(onClick = { saveSeries(current) }) { Text("Celou sérii") }
                    TextButton(onClick = { showSaveChoice = false }) { Text("Zrušit") }
                }
            },
        )
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
                    if (occurrence) {
                        TextButton(onClick = { commit { repository.deleteOccurrence(id, current.instanceBegin!!); null } }) {
                            Text("Jen tento výskyt")
                        }
                    }
                    if (changedOccurrence) {
                        TextButton(onClick = { commit { repository.cancelOccurrence(id); null } }) { Text("Jen tento výskyt") }
                    }
                    TextButton(onClick = { commit { repository.deleteEvent(current.originalId ?: id); null } }) {
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
    statuses: List<Attendee>,
    originalRule: String?,
    editable: Boolean,
    canChangeCalendar: Boolean,
    recurring: Boolean,
    changedOccurrence: Boolean,
    autoHint: String,
    onChange: (EventDraft) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!editable) {
            Hint("Tento kalendář je jen pro čtení.")
        } else if (changedOccurrence) {
            Hint("Samostatně změněný výskyt opakované události, úpravy platí jen pro něj.")
        } else if (recurring) {
            Hint("Opakovaná událost. Při uložení vybereš, jestli změnit jen tento výskyt, nebo celou sérii.")
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
        // A single changed occurrence cannot repeat on its own
        ChoiceRow("Opakování", repeatChoices(originalRule), draft.rule, editable && !changedOccurrence) {
            onChange(draft.copy(rule = it))
        }
        // Reminder settings live only in this app, so they work even for read-only calendars
        ChoiceRow("Upozornění", watchOptions, draft.watch, enabled = true) { onChange(draft.copy(watch = it)) }
        if (draft.watch == Watch.AUTO) Hint(autoHint)
        if (recurring && draft.watch != Watch.AUTO) Hint("Platí pro všechna opakování.")
        ChoiceRow("Priorita", priorityOptions, draft.priority, enabled = draft.watch != Watch.NEVER) {
            onChange(draft.copy(priority = it))
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

        AttendeesSection(draft.attendees, statuses, editable) { onChange(draft.copy(attendees = it)) }
    }
}

@Composable
private fun AttendeesSection(
    emails: List<String>,
    statuses: List<Attendee>,
    editable: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var newEmail by rememberSaveable { mutableStateOf("") }

    SectionTitle("Účastníci")
    emails.forEach { email ->
        val status = statuses.find { it.email.equals(email, ignoreCase = true) }?.statusLabel ?: "Nový"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(email)
                Hint(status)
            }
            if (editable) {
                IconButton(onClick = { onChange(emails - email) }) {
                    Icon(Icons.Default.Close, contentDescription = "Odebrat $email")
                }
            }
        }
    }
    if (editable) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newEmail,
                onValueChange = { newEmail = it.trim() },
                label = { Text("Přidat e-mail") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = newEmail.contains('@') && emails.none { it.equals(newEmail, ignoreCase = true) },
                onClick = {
                    onChange(emails + newEmail)
                    newEmail = ""
                },
            ) { Text("Přidat") }
        }
        Hint("Google může novým účastníkům poslat pozvánku.")
    } else if (emails.isEmpty()) {
        Hint("Žádní účastníci")
    }
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

private fun pickDate(context: Context, date: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth)
        .apply { datePicker.firstDayOfWeek = java.util.Calendar.MONDAY }
        .show()
}

private fun pickTime(context: Context, time: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, hour, minute -> onPick(LocalTime.of(hour, minute)) }, time.hour, time.minute, true).show()
}
