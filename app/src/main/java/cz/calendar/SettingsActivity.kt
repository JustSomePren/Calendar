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
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    LaunchedEffect(Unit) {
        calendars = withContext(Dispatchers.IO) { repository.calendars() }
    }

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

@Composable
private fun ImportSection(calendars: List<CalendarInfo>, repository: CalendarRepository, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember(calendars) {
        mutableStateOf(calendars.find { it.id == settings.lastCalendar }?.id ?: calendars.find { it.primary }?.id ?: calendars.firstOrNull()?.id)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val calendarId = target ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
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
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    SectionTitle("Import ze Samsung Kalendáře")
    Hint(
        "Samsung kalendář se na Pixelu synchronizovat nedá. Na telefonu Samsung události vyexportuj nebo sdílej " +
            "jako soubor .ics (postup se liší podle verze One UI), pošli si ho do tohoto telefonu a vyber ho tady. " +
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
