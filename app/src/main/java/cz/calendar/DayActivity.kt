package cz.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val DAY_PAGES = 20_000
private const val START_DAY = DAY_PAGES / 2

// All events of one day, grouped by importance; swipe to the next or previous day
class DayActivity : ComponentActivity() {

    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!hasCalendarAccess()) {
            finish()
            return
        }
        val day = intent.dayExtra() ?: LocalDate.now()
        setContent {
            CalendarTheme {
                DayScreen(CalendarRepository(contentResolver), Settings(this), day, refreshTick.intValue, onClose = ::finish)
            }
        }
    }

    // Back from the editor the day shows the change
    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }
}

private val titleFormat = DateTimeFormatter.ofPattern("EEEE d. MMMM", czech)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayScreen(repository: CalendarRepository, settings: Settings, day: LocalDate, refreshTick: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = START_DAY) { DAY_PAGES }
    fun dayOf(page: Int): LocalDate = day.plusDays((page - START_DAY).toLong())
    val shown = dayOf(pager.currentPage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shown.format(titleFormat).capitalized()) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { context.startActivity(newEventIntent(context, shown)) }, shape = CircleShape) {
                Icon(Icons.Default.Add, contentDescription = "Přidat událost")
            }
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(), beyondViewportPageCount = 1) { page ->
            DayEvents(dayOf(page), repository, settings, refreshTick)
        }
    }
}

@Composable
private fun DayEvents(day: LocalDate, repository: CalendarRepository, settings: Settings, refreshTick: Int) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<CalendarEvent>?>(null) }
    LaunchedEffect(day, refreshTick) {
        events = withContext(Dispatchers.IO) {
            repository.eventsByDay(day, day, settings.hiddenCalendars, settings.eventsWithPriority(Priority.HIGH), settings.ranking())[day].orEmpty()
        }
    }
    val list = events ?: return

    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Žádné události", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Groups in the order of importance, each sorted by time
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Hint("${list.size} ${eventCountWord(list.size)}") }
        list.groupBy { it.rank }.toSortedMap().forEach { (rank, group) ->
            item(key = "rank$rank") { SectionTitle(rankLabels.getOrElse(rank) { "" }) }
            items(group, key = { "${it.eventId}:${it.begin}" }) { event ->
                EventRow(event, onClick = { context.startActivity(openEventIntent(context, event)) })
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

private fun eventCountWord(count: Int) = when {
    count == 1 -> "událost"
    count in 2..4 -> "události"
    else -> "událostí"
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val days = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else zone)
    val dateFormat = DateTimeFormatter.ofPattern("d. M.")
    // Events running over several days show the dates, not just the hours
    val timeFormat = DateTimeFormatter.ofPattern(if (event.multiDay) "d. M. HH:mm" else "HH:mm")
    val time = when {
        event.allDay && event.multiDay -> "Celý den, ${days.start.format(dateFormat)} – ${days.endInclusive.format(dateFormat)}"
        event.allDay -> "Celý den"
        else -> {
            val begin = Instant.ofEpochMilli(event.begin).atZone(zone).format(timeFormat)
            val end = Instant.ofEpochMilli(event.end).atZone(zone).format(timeFormat)
            "$begin – $end"
        }
    }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp).height(36.dp).background(Color(event.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(event.label, style = MaterialTheme.typography.bodyLarge)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
