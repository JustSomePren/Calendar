package cz.calendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val START_PAGE = 600
private val chipStyle = TextStyle(fontSize = 10.sp, lineHeight = 15.sp)

private fun monthForPage(page: Int) = YearMonth.now().plusMonths((page - START_PAGE).toLong())

private fun pageForDay(day: LocalDate) = START_PAGE + ChronoUnit.MONTHS.between(YearMonth.now(), YearMonth.from(day)).toInt()

fun eventTextColor(event: CalendarEvent, default: Color): Color {
    val color = Color(event.color)
    return when {
        !(event.allDay || event.multiDay) -> default
        color.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
}

// Day number takes the top 24dp, every event row below it 17dp
// Sizes grow with the system font size, so large text gets taller rows instead of being cut
private fun dayHeader(scale: Float) = 24.dp * scale
private fun rowHeight(scale: Float) = 17.dp * scale

private fun rowOffset(row: Int, scale: Float) = dayHeader(scale) + rowHeight(scale) * row + 1.dp

val CalendarEvent.label: String
    get() = if (important) "! $title" else title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(
    repository: CalendarRepository,
    settings: Settings,
    refreshTick: Int,
    openDay: LocalDate?,
    onOpenDayHandled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = START_PAGE) { START_PAGE * 2 }

    // Visibility and priorities change in other screens, so read them again on every refresh
    var hidden by remember(refreshTick) { mutableStateOf(settings.hiddenCalendars) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    LaunchedEffect(refreshTick) {
        calendars = withContext(Dispatchers.IO) { repository.calendars() }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = syncPickedAccount(result.data) ?: return@rememberLauncherForActivityResult
        Toast.makeText(context, "Kalendáře účtu ${account.name} se synchronizují, za chvíli se objeví.", Toast.LENGTH_LONG).show()
    }
    val important = remember(refreshTick) { settings.eventsWithPriority(Priority.HIGH) }
    // Read again on every refresh, so "today" moves after midnight
    val today = remember(refreshTick) { LocalDate.now() }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(openDay) {
        openDay ?: return@LaunchedEffect
        pagerState.scrollToPage(pageForDay(openDay))
        selectedDay = openDay
        onOpenDayHandled()
    }

    // Swiping would fight with paging through months, so the menu opens from its button only
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            CalendarDrawer(
                calendars = calendars,
                hidden = hidden,
                onToggle = { id ->
                    hidden = if (id in hidden) hidden - id else hidden + id
                    settings.hiddenCalendars = hidden
                },
                onOpen = { intent ->
                    scope.launch { drawerState.close() }
                    context.startActivity(intent)
                },
                onAddAccount = {
                    scope.launch { drawerState.close() }
                    accountPicker.launch(chooseAccountIntent())
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(monthTitle(monthForPage(pagerState.currentPage))) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Kalendáře a účty")
                        }
                    },
                    actions = {
                        TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(START_PAGE) } }) {
                            Text("Dnes")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { context.startActivity(newEventIntent(context, LocalDate.now())) }) {
                    Icon(Icons.Default.Add, contentDescription = "Nová událost")
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                WeekdayHeader()
                HorizontalPager(pagerState, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                    val month = monthForPage(page)
                    val days = remember(month) { gridDays(month) }
                    var events by remember { mutableStateOf(emptyMap<LocalDate, List<CalendarEvent>>()) }
                    LaunchedEffect(month, refreshTick, hidden) {
                        events = withContext(Dispatchers.IO) { repository.eventsByDay(days.first(), days.last(), hidden, important) }
                    }
                    var photos by remember { mutableStateOf(emptyMap<LocalDate, ImageBitmap>()) }
                    LaunchedEffect(month, refreshTick) {
                        photos = withContext(Dispatchers.IO) {
                            if (settings.showPhotos && GalleryLink.installed(context)) {
                                GalleryLink.covers(context, days.first(), days.last()).mapValues { it.value.asImageBitmap() }
                            } else {
                                emptyMap()
                            }
                        }
                    }
                    MonthGrid(
                        month = month,
                        days = days,
                        events = events,
                        photos = photos,
                        today = today,
                        onDayClick = { selectedDay = it },
                        // Holding a day alone starts a usual event, dragging over more days an all-day one
                        onRangeSelected = { from, to ->
                            context.startActivity(newEventIntent(context, from, to.takeIf { it != from }))
                        },
                    )
                }
            }
        }
    }

    selectedDay?.let { day ->
        DaySheet(day, repository, hidden, important, refreshTick, onDismiss = { selectedDay = null })
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
    photos: Map<LocalDate, ImageBitmap>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    onRangeSelected: (LocalDate, LocalDate) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Days picked by holding one and dragging over others
    var picked by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    val range = picked?.let { (a, b) -> minOf(a, b)..maxOf(a, b) }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(days) {
                fun dayAt(position: Offset): LocalDate {
                    val row = (position.y / (size.height / 6f)).toInt().coerceIn(0, 5)
                    val col = (position.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                    return days[row * 7 + col]
                }
                // One detector for taps and hold-and-drag; a clickable cell would take the touch first.
                // A quick swipe is left alone, so the pager still flips months.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hold = awaitLongPressOrCancellation(down.id)
                    if (hold == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        val tapped = up != null && !up.pressed && !up.isConsumed &&
                            (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                        if (tapped) onDayClick(dayAt(down.position))
                        return@awaitEachGesture
                    }

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val first = dayAt(hold.position)
                    picked = first to first
                    val finished = drag(hold.id) { change ->
                        change.consume()
                        picked = first to dayAt(change.position)
                    }
                    if (finished) picked?.let { (a, b) -> onRangeSelected(minOf(a, b), maxOf(a, b)) }
                    picked = null
                }
            },
    ) {
        days.chunked(7).forEach { week ->
            HorizontalDivider()
            WeekRow(week, month, events, photos, today, range, onDayClick, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekRow(
    week: List<LocalDate>,
    month: YearMonth,
    events: Map<LocalDate, List<CalendarEvent>>,
    photos: Map<LocalDate, ImageBitmap>,
    today: LocalDate,
    range: ClosedRange<LocalDate>?,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidth = maxWidth / 7
        val slots = ((maxHeight - dayHeader(scale)) / rowHeight(scale)).toInt()
        val layout = remember(week, events, slots) { layoutWeek(week, events, slots) }

        Row(Modifier.fillMaxSize()) {
            week.forEachIndexed { col, day ->
                DayCell(
                    day = day,
                    isToday = day == today,
                    picked = range?.contains(day) == true,
                    inMonth = YearMonth.from(day) == month,
                    photo = photos[day],
                    singles = layout.singles[col],
                    hidden = layout.hidden[col],
                    moreRow = layout.moreRow[col],
                    // Taps come from the grid's gesture detector, screen readers use this action
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { onClick(label = "Otevřít den") { onDayClick(day); true } },
                )
            }
        }

        // Spanning events are drawn over the cells as one bar
        layout.bars.forEach { bar ->
            EventChip(
                event = bar.event,
                startsHere = bar.startsHere,
                endsHere = bar.endsHere,
                modifier = Modifier
                    .offset(x = cellWidth * bar.fromCol, y = rowOffset(bar.row, scale))
                    .width(cellWidth * (bar.toCol - bar.fromCol + 1)),
            )
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    isToday: Boolean,
    picked: Boolean,
    inMonth: Boolean,
    photo: ImageBitmap?,
    singles: List<Pair<Int, CalendarEvent>>,
    hidden: Int,
    moreRow: Int,
    modifier: Modifier,
) {
    val scale = LocalDensity.current.fontScale
    val pickedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Box(modifier.background(if (picked) pickedColor else Color.Transparent).alpha(if (inMonth) 1f else 0.4f)) {
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Faded, so the day number and events stay readable
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)))
            if (picked) Box(Modifier.fillMaxSize().background(pickedColor))
        }
        DayNumber(day, isToday, Modifier.align(Alignment.TopCenter))
        singles.forEach { (row, event) ->
            EventChip(event, startsHere = true, endsHere = true, modifier = Modifier.offset(y = rowOffset(row, scale)).fillMaxWidth())
        }
        if (hidden > 0) {
            Text("+$hidden", Modifier.offset(y = rowOffset(moreRow, scale)).padding(start = 3.dp), style = chipStyle)
        }
    }
}

@Composable
private fun DayNumber(day: LocalDate, isToday: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = day.dayOfMonth.toString(),
        modifier = modifier
            .padding(top = 2.dp)
            .size(22.dp * LocalDensity.current.fontScale)
            .background(if (isToday) colors.primary else Color.Transparent, CircleShape)
            .semantics { if (isToday) stateDescription = "dnes" },
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
private fun EventChip(event: CalendarEvent, startsHere: Boolean, endsHere: Boolean, modifier: Modifier) {
    val color = Color(event.color)
    val filled = event.allDay || event.multiDay
    val start = if (startsHere) 3.dp else 0.dp
    val end = if (endsHere) 3.dp else 0.dp
    Text(
        text = event.label,
        modifier = modifier
            .height(16.dp * LocalDensity.current.fontScale)
            .padding(start = if (startsHere) 1.dp else 0.dp, end = if (endsHere) 1.dp else 0.dp)
            .background(
                if (filled) color else color.copy(alpha = 0.2f),
                RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
            )
            .padding(horizontal = 2.dp),
        style = chipStyle,
        color = eventTextColor(event, MaterialTheme.colorScheme.onSurface),
        // Cut at the cell edge instead of wrapping, so "! Zubař" does not shrink to "!"
        softWrap = false,
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
    important: Set<Long>,
    refreshTick: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    var photos by remember { mutableStateOf(emptyMap<CalendarEvent, Int>()) }
    LaunchedEffect(day, refreshTick) {
        events = withContext(Dispatchers.IO) { repository.eventsByDay(day, day, hidden, important)[day].orEmpty() }
        photos = withContext(Dispatchers.IO) { photoCounts(context, events) }
    }

    // Opens fully, so the add button under a long list is on screen
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                day.format(DateTimeFormatter.ofPattern("EEEE d. MMMM", czech)).capitalized(),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))

            if (events.isEmpty()) {
                Text("Žádné události", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Leaves room for the add button below even on a busy day
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(events) { event ->
                    EventRow(event, photos[event], onClick = { context.startActivity(openEventIntent(context, event)) })
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { context.startActivity(newEventIntent(context, day)) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Přidat událost")
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, photos: Int?, onClick: () -> Unit) {
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
        Column(Modifier.weight(1f)) {
            Text(event.label, style = MaterialTheme.typography.bodyLarge)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Photos from the Galerie app; the event detail shows them too
        if (photos != null) {
            val context = LocalContext.current
            val vacation = Settings(context).vacation(event.eventId) != null
            TextButton(onClick = { GalleryLink.open(context, event.title, GalleryLink.window(event, vacation)) }) {
                Text(photosLabel(photos))
            }
        }
    }
}
