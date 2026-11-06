package cz.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private const val START_PAGE = 600
private val czech = Locale.forLanguageTag("cs")
private val weekdays = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
private val chipStyle = TextStyle(fontSize = 10.sp, lineHeight = 15.sp)

private fun monthForPage(page: Int) = YearMonth.now().plusMonths((page - START_PAGE).toLong())

private fun gridDays(month: YearMonth): List<LocalDate> {
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return List(42) { start.plusDays(it.toLong()) }
}

private fun String.capitalized() = replaceFirstChar { it.titlecase(czech) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(repository: CalendarRepository, settings: Settings, refreshTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = START_PAGE) { START_PAGE * 2 }

    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var hidden by remember { mutableStateOf(settings.hiddenCalendars) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        calendars = withContext(Dispatchers.IO) { repository.calendars() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val month = monthForPage(pagerState.currentPage)
                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", czech)).capitalized())
                },
                actions = {
                    TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(START_PAGE) } }) {
                        Text("Dnes")
                    }
                    IconButton(onClick = { showFilter = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Kalendáře")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { context.startActivity(newEventIntent(LocalDate.now())) }) {
                Icon(Icons.Default.Add, contentDescription = "Nová událost")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            WeekdayHeader()
            HorizontalPager(pagerState, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                val month = monthForPage(page)
                val days = remember(month) { gridDays(month) }
                val events by produceState(emptyMap(), month, refreshTick, hidden) {
                    value = withContext(Dispatchers.IO) { repository.eventsByDay(days.first(), days.last(), hidden) }
                }
                MonthGrid(month, days, events, onDayClick = { selectedDay = it })
            }
        }
    }

    selectedDay?.let { day ->
        DaySheet(day, repository, hidden, refreshTick, onDismiss = { selectedDay = null })
    }

    if (showFilter) {
        CalendarFilterDialog(
            calendars = calendars,
            hidden = hidden,
            onChange = {
                hidden = it
                settings.hiddenCalendars = it
            },
            onDismiss = { showFilter = false },
        )
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        weekdays.forEachIndexed { index, name ->
            Text(
                name,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = if (index == 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    days: List<LocalDate>,
    events: Map<LocalDate, List<CalendarEvent>>,
    onDayClick: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        days.chunked(7).forEach { week ->
            HorizontalDivider()
            Row(Modifier.weight(1f)) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = YearMonth.from(day) == month,
                        events = events[day].orEmpty(),
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onDayClick(day) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, inMonth: Boolean, events: List<CalendarEvent>, modifier: Modifier) {
    BoxWithConstraints(modifier.alpha(if (inMonth) 1f else 0.4f).padding(horizontal = 1.dp)) {
        val slots = ((maxHeight - 24.dp) / 17.dp).toInt().coerceAtLeast(0)
        val visible = if (events.size > slots) events.take((slots - 1).coerceAtLeast(0)) else events

        Column {
            DayNumber(day)
            visible.forEach { EventChip(it) }
            if (events.size > visible.size) {
                Text("+${events.size - visible.size}", style = chipStyle, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.DayNumber(day: LocalDate) {
    val isToday = day == LocalDate.now()
    val colors = MaterialTheme.colorScheme
    Text(
        text = day.dayOfMonth.toString(),
        modifier = Modifier
            .padding(top = 2.dp)
            .align(Alignment.CenterHorizontally)
            .size(22.dp)
            .background(if (isToday) colors.primary else Color.Transparent, CircleShape),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 22.sp),
        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        color = when {
            isToday -> colors.onPrimary
            day.dayOfWeek == DayOfWeek.SUNDAY -> colors.error
            else -> colors.onSurface
        },
    )
}

@Composable
private fun EventChip(event: CalendarEvent) {
    val color = Color(event.color)
    val filled = event.allDay || event.multiDay
    Text(
        text = event.title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp)
            .height(16.dp)
            .background(if (filled) color else color.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp),
        style = chipStyle,
        color = when {
            !filled -> MaterialTheme.colorScheme.onSurface
            color.luminance() > 0.5f -> Color.Black
            else -> Color.White
        },
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(
    day: LocalDate,
    repository: CalendarRepository,
    hidden: Set<Long>,
    refreshTick: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val events by produceState(emptyList(), day, refreshTick, hidden) {
        value = withContext(Dispatchers.IO) { repository.eventsByDay(day, day, hidden)[day].orEmpty() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                day.format(DateTimeFormatter.ofPattern("EEEE d. MMMM", czech)).capitalized(),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))

            if (events.isEmpty()) {
                Text("Žádné události", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(events) { event ->
                    EventRow(event, onClick = { context.startActivity(openEventIntent(event)) })
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { context.startActivity(newEventIntent(day)) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Přidat událost")
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.systemDefault()
    val time = if (event.allDay) {
        "Celý den"
    } else {
        val begin = Instant.ofEpochMilli(event.begin).atZone(zone).format(timeFormat)
        val end = Instant.ofEpochMilli(event.end).atZone(zone).format(timeFormat)
        "$begin – $end"
    }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp).height(36.dp).background(Color(event.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(event.title, style = MaterialTheme.typography.bodyLarge)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarFilterDialog(
    calendars: List<CalendarInfo>,
    hidden: Set<Long>,
    onChange: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Hotovo") } },
        title = { Text("Kalendáře") },
        text = {
            LazyColumn {
                items(calendars) { calendar ->
                    val checked = calendar.id !in hidden
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChange(if (checked) hidden + calendar.id else hidden - calendar.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Color(calendar.color)),
                            modifier = Modifier.padding(12.dp),
                        )
                        Column {
                            Text(calendar.name)
                            Text(
                                calendar.account,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}
