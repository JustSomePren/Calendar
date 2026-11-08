package cz.calendar

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GOOGLE_CALENDAR = "com.google.android.calendar"

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!hasCalendarAccess()) {
            finish()
            return
        }
        setContent {
            CalendarTheme {
                SettingsScreen(CalendarRepository(contentResolver), Settings(this), onClose = ::finish)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        BackgroundWork.refreshNow(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(repository: CalendarRepository, settings: Settings, onClose: () -> Unit) {
    var all by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { repository.calendars() }
    }
    val calendars = all.filter { it.synced }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nastavení") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Zavřít") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CalendarsSection(calendars, settings)
            WorkFilterSection(settings)
            NotificationsSection()
            CopySection(all, calendars.filter { it.writable }, repository)
            ImportSection(calendars.filter { it.writable }, repository, settings)
        }
    }
}

@Composable
private fun CalendarsSection(calendars: List<CalendarInfo>, settings: Settings) {
    var hidden by remember { mutableStateOf(settings.hiddenCalendars) }
    var categories by remember(calendars) { mutableStateOf(calendars.associate { it.id to settings.category(it.id) }) }

    SectionTitle("Kalendáře")
    Hint("Zaškrtnuté kalendáře se zobrazují. Kategorie určuje, na co tě aplikace upozorní.")
    Category.entries.forEach { Hint("• ${it.label}: ${it.hint.replaceFirstChar { c -> c.lowercase() }}") }

    calendars.forEach { calendar ->
        val visible = calendar.id !in hidden
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = visible,
                onCheckedChange = {
                    hidden = if (visible) hidden + calendar.id else hidden - calendar.id
                    settings.hiddenCalendars = hidden
                },
            )
            Box(Modifier.size(12.dp).background(Color(calendar.color), CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(calendar.name)
                Hint(calendar.account)
            }
            ChoiceButton(
                options = Category.entries.map { it to it.label },
                selected = categories[calendar.id] ?: Category.PERSONAL,
                enabled = true,
            ) {
                settings.setCategory(calendar.id, it)
                categories = categories + (calendar.id to it)
            }
        }
    }
}

@Composable
private fun WorkFilterSection(settings: Settings) {
    var emails by remember { mutableStateOf(settings.workEmails.joinToString(", ")) }
    var keywords by remember { mutableStateOf(settings.workKeywords.joinToString(", ")) }

    SectionTitle("Pracovní filtr")
    Hint(
        "U pracovních kalendářů přijde upozornění jen na události, kde je některý e-mail mezi účastníky " +
            "(a událost neodmítl), nebo které obsahují některé ze slov v názvu, poznámce či místě.",
    )
    OutlinedTextField(
        value = emails,
        onValueChange = {
            emails = it
            settings.workEmails = splitList(it)
        },
        label = { Text("E-maily účastníků, oddělené čárkou") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = keywords,
        onValueChange = {
            keywords = it
            settings.workKeywords = splitList(it)
        },
        label = { Text("Slova v události, např. testProjekt") },
        modifier = Modifier.fillMaxWidth(),
    )
    if (splitList(emails).isEmpty() && splitList(keywords).isEmpty()) {
        Hint("Filtr je prázdný, z pracovních kalendářů teď nepřijde žádné upozornění.")
    }
}

@Composable
private fun NotificationsSection() {
    val context = LocalContext.current

    SectionTitle("Upozornění")
    Hint(
        "Kdy přijde upozornění:\n" +
            "• vícedenní událost: 3 dny předem a den předem v 18:00\n" +
            "• celodenní: den předem v 18:00\n" +
            "• ráno (do 9:00): večer předem ve 20:00 a 30 min předem\n" +
            "• dopoledne (do 12:00): týž den v 7:30 a 30 min předem\n" +
            "• ostatní: 30 min předem\n" +
            "• opakovaná událost nebo nízká priorita: jen to nejbližší\n" +
            "• vysoká priorita: navíc dřív (2 h a večer předem, u celodenních i ráno v 8:00)\n" +
            "U každé události jde v editoru zvolit Upozornění „Vždy (sledovat)“ nebo „Nikdy“ " +
            "a prioritu. Vypnout ji jde i tlačítkem „Neupozorňovat“ přímo v oznámení.",
    )
    Hint("Aby upozornění nechodila dvakrát, vypni je v aplikaci Google Kalendář.")
    OutlinedButton(onClick = {
        val intent = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SystemSettings.EXTRA_APP_PACKAGE, GOOGLE_CALENDAR)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "Google Kalendář není nainstalovaný", Toast.LENGTH_SHORT).show() }
    }) { Text("Upozornění Google Kalendáře") }
    OutlinedButton(onClick = {
        val intent = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    }) { Text("Upozornění této aplikace") }
}

private fun defaultImportCalendar(calendars: List<CalendarInfo>, settings: Settings): Long? =
    (calendars.find { it.id == settings.lastCalendar } ?: calendars.find { it.primary } ?: calendars.firstOrNull())?.id

// Copies a whole calendar into another, e.g. Samsung's calendar into Google on the old phone
@Composable
private fun CopySection(sources: List<CalendarInfo>, targets: List<CalendarInfo>, repository: CalendarRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by rememberSaveable { mutableStateOf<Long?>(null) }
    var to by rememberSaveable { mutableStateOf<Long?>(null) }
    var copying by remember { mutableStateOf(false) }

    SectionTitle("Zkopírovat kalendář")
    Hint(
        "Na starém telefonu Samsung tu vyber Samsung kalendář (nebo „Můj kalendář“) a jako cíl svůj Google kalendář. " +
            "Události se zkopírují i s opakováním, Google je pak pošle do nového telefonu. Kopírovat jde opakovaně, " +
            "události, které už v cíli jsou, se přeskočí. Účastníci se nekopírují, aby nikomu nepřišla pozvánka znovu.",
    )
    ChoiceRow("Z kalendáře", sources.filter { it.id != to }.map { it.id to "${it.name} (${it.account})" }, from, enabled = true) { from = it }
    ChoiceRow("Do kalendáře", targets.filter { it.id != from }.map { it.id to "${it.name} (${it.account})" }, to, enabled = true) { to = it }
    OutlinedButton(
        enabled = from != null && to != null && !copying,
        onClick = {
            val source = from ?: return@OutlinedButton
            val target = to ?: return@OutlinedButton
            copying = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val events = repository.copyableEvents(source)
                        events.size to repository.import(events, target)
                    }
                }
                copying = false
                val message = result.fold(
                    onSuccess = { (found, added) -> "Zkopírováno $added z $found událostí, ${found - added} už v cíli bylo." },
                    onFailure = { "Kopírování se nepovedlo." },
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        },
    ) { Text(if (copying) "Kopíruji…" else "Zkopírovat") }
}

@Composable
private fun ImportSection(calendars: List<CalendarInfo>, repository: CalendarRepository, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved with the screen, the file picker result may come back to a recreated one
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(calendars) {
        if (target == null) target = defaultImportCalendar(calendars, settings)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val chosen = target
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val calendarId = chosen ?: defaultImportCalendar(repository.calendars().filter { it.writable && it.synced }, settings)
                        ?: error("No calendar to import into")
                    val events = uris.flatMap { uri ->
                        context.contentResolver.openInputStream(uri)?.use { parseIcs(it.reader().readText()) }.orEmpty()
                    }
                    events.size to repository.import(events, calendarId)
                }
            }
            val message = result.fold(
                onSuccess = { (found, added) -> "Nalezeno $found událostí, přidáno $added, ${found - added} už v kalendáři bylo." },
                onFailure = { "Soubor se nepodařilo načíst." },
            )
            // The result may come back to a recreated screen, so make sure the toast runs on the main thread
            withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }

    SectionTitle("Import ze souboru")
    Hint(
        "Soubor .ics nebo .vcs (např. sdílená událost ze Samsung Kalendáře) pošli do tohoto telefonu a vyber ho tady. " +
            "Události, které už v kalendáři jsou, se přeskočí.",
    )
    if (calendars.isEmpty()) {
        Hint("Není kam importovat, v telefonu chybí kalendář s možností zápisu.")
    } else {
        ChoiceRow("Importovat do", calendars.map { it.id to it.name }, target, enabled = true) { target = it }
        OutlinedButton(onClick = { picker.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/octet-stream", "*/*")) }) {
            Text("Vybrat soubory .ics")
        }
    }
}
