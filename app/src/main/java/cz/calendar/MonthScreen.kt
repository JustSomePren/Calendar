package cz.calendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.app.Activity
import android.content.Intent
import android.accounts.Account
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
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

// Saturday in blue and Sunday in red, as Samsung Calendar shows them
@Composable
fun weekendColor(day: DayOfWeek): Color? = when (day) {
    DayOfWeek.SATURDAY -> if (isSystemInDarkTheme()) Color(0xFF8AB4F8) else Color(0xFF1A62C9)
    DayOfWeek.SUNDAY -> MaterialTheme.colorScheme.error
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(repository: CalendarRepository, settings: Settings, refreshTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = START_PAGE) { START_PAGE * 2 }
    // Bumped here when the menu changes calendars, refreshTick comes from the activity
    var changes by remember { mutableIntStateOf(0) }

    // Visibility and priorities change in other screens, so read them again on every refresh
    var hidden by remember(refreshTick) { mutableStateOf(settings.hiddenCalendars) }
    var onlyMine by remember { mutableStateOf(settings.onlyMine) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    // What keeps Google calendars from the phone; read again whenever the app comes back
    var syncFacts by remember { mutableStateOf<SyncFacts?>(null) }
    val syncProblem = syncFacts?.let { syncProblem(it) }
    suspend fun readFacts(): SyncFacts? =
        // A check that fails says nothing rather than closing the app
        withContext(Dispatchers.IO) { runCatching { readSyncFacts(context, calendars, settings.googleAccounts) }.getOrNull() }
    LaunchedEffect(refreshTick, changes) {
        calendars = withContext(Dispatchers.IO) { repository.calendars() }
        val facts = readFacts()
        syncFacts = facts
        // Accounts set to sync whose calendars are still missing, e.g. after sharing was just switched
        // on in Google Calendar: ask for the sync now instead of waiting for the phone's own schedule
        if (facts != null && facts.googleCalendars == 0) {
            withContext(Dispatchers.IO) { facts.accounts.filter { it.on }.forEach { runCatching { requestCalendarSync(it.account) } } }
        }
    }
    val important = remember(refreshTick) { settings.eventsWithPriority(Priority.HIGH) }
    // Read again on every refresh, so "today" moves after midnight
    val today = remember(refreshTick) { LocalDate.now() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var newCalendar by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CalendarInfo?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    // The account whose calendars are being synced right now, and why they did not come
    var syncing by remember { mutableStateOf<Account?>(null) }
    var accountProblem by remember { mutableStateOf<Pair<Account, SyncProblem>?>(null) }

    fun fix(problem: SyncProblem) {
        scope.launch {
            fixSync(context, problem, syncFacts ?: readFacts() ?: return@launch)
            changes++
        }
    }

    // Switches the account's calendar sync on, waits for the sync and shows what came of it
    fun onAccountPicked(account: Account) {
        addingAccount = false
        settings.googleAccounts = settings.googleAccounts + account.name
        syncing = account
        scope.launch {
            syncAccount(account)
            awaitSync(account)
            calendars = withContext(Dispatchers.IO) { repository.calendars() }
            val facts = readFacts()
            syncFacts = facts
            syncing = null
            if (calendarsOf(calendars, account).isNotEmpty()) {
                Toast.makeText(context, "Kalendáře účtu ${account.name} jsou v menu.", Toast.LENGTH_LONG).show()
                drawerState.open()
            } else {
                // Everything else in order means Google Calendar keeps its data to itself
                accountProblem = account to (facts?.let { syncProblem(it) } ?: SyncProblem.SHARING_OFF)
            }
            changes++
        }
    }
    var pickMonth by remember { mutableStateOf(false) }
    var peek by remember { mutableStateOf<Pair<LocalDate, List<CalendarEvent>>?>(null) }
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickedAccount(result.data)?.let { onAccountPicked(it) }
    }
    // The phone's add-account screen says nothing about the outcome, so the new account is picked afterwards
    val systemAddAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        accountPicker.launch(chooseAccountIntent())
    }
    fun addNewAccount() {
        addingAccount = false
        val activity = context as? Activity
        val shown = activity != null && addGoogleAccount(activity) { account -> account?.let { onAccountPicked(it) } }
        if (shown) return
        val intent = addGoogleAccountIntent()
        if (canStart(context, intent)) {
            systemAddAccount.launch(intent)
        } else {
            Toast.makeText(context, "Přidej Google účet v Nastavení telefonu → Hesla a účty, pak ho tady vyber.", Toast.LENGTH_LONG).show()
        }
    }

    fun closeDrawer() = scope.launch { drawerState.close() }

    // The first time calendars cannot come the menu opens by itself, so the fix is not hidden
    LaunchedEffect(syncProblem) {
        if (syncProblem != null && !settings.syncHelpShown) {
            settings.syncHelpShown = true
            drawerState.open()
        }
    }

    // Swiping would fight with paging through months, so the menu opens from its button only
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            CalendarDrawer(
                syncProblem = syncProblem,
                onFixSync = ::fix,
                calendars = calendars,
                hidden = hidden,
                onlyMine = onlyMine,
                onOnlyMine = {
                    onlyMine = it
                    settings.onlyMine = it
                    scope.launch { refreshMonthWidgets(context) }
                },
                onToggle = { calendar ->
                    if (!calendar.synced) {
                        // A calendar the phone does not sync yet: switch it on and show it
                        hidden = hidden - calendar.id
                        settings.hiddenCalendars = hidden
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.startSyncing(calendar) }
                            changes++
                        }
                    } else {
                        hidden = if (calendar.id in hidden) hidden - calendar.id else hidden + calendar.id
                        settings.hiddenCalendars = hidden
                    }
                },
                onRemove = { removing = it },
                onAddAccount = {
                    closeDrawer()
                    addingAccount = true
                },
                onNewCalendar = {
                    closeDrawer()
                    newCalendar = true
                },
                onSyncNow = {
                    closeDrawer()
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.syncAll(calendars) }
                        Toast.makeText(context, "Synchronizace spuštěna, změny se za chvíli objeví.", Toast.LENGTH_LONG).show()
                    }
                },
                onSettings = {
                    closeDrawer()
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { MonthTitle(monthForPage(pagerState.currentPage), onClick = { pickMonth = true }) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Kalendáře a účty")
                        }
                    },
                    actions = {
                        TodayButton(today) { scope.launch { pagerState.animateScrollToPage(START_PAGE) } }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { context.startActivity(newEventIntent(context, LocalDate.now())) }, shape = CircleShape) {
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
                    LaunchedEffect(month, refreshTick, hidden, changes, onlyMine) {
                        events = withContext(Dispatchers.IO) {
                            repository.eventsByDay(days.first(), days.last(), hidden, important, settings.ranking())
                        }
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
                        onDayClick = { day ->
                            val list = events[day].orEmpty()
                            if (list.size > PEEK_LIMIT) context.startActivity(dayIntent(context, day)) else peek = day to list
                        },
                        // Holding a day alone starts a usual event, dragging over more days an all-day one
                        onRangeSelected = { from, to ->
                            context.startActivity(newEventIntent(context, from, to.takeIf { it != from }))
                        },
                    )
                }
            }
        }
    }

    if (addingAccount) {
        AlertDialog(
            onDismissRequest = { addingAccount = false },
            title = { Text("Přidat účet Google") },
            text = {
                Text(
                    "Google kalendáře chodí do telefonu přes Google účet v jeho nastavení. Vyber účet, který už v telefonu je, " +
                        "aplikace u něj zapne synchronizaci Kalendáře a stáhne ho. Nebo se přihlas k dalšímu účtu.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        addingAccount = false
                        accountPicker.launch(chooseAccountIntent())
                    }) { Text("Účet, který už je v telefonu") }
                    TextButton(onClick = { addNewAccount() }) { Text("Přihlásit další účet") }
                    TextButton(onClick = { addingAccount = false }) { Text("Zrušit") }
                }
            },
        )
    }

    syncing?.let { account ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Stahují se kalendáře") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Účet ${account.name} se synchronizuje, chvíli to trvá.")
                }
            },
            confirmButton = { TextButton(onClick = { syncing = null }) { Text("Na pozadí") } },
        )
    }

    accountProblem?.let { (account, problem) ->
        AlertDialog(
            onDismissRequest = { accountProblem = null },
            title = { Text("Kalendáře účtu ${account.name} nedorazily") },
            text = { Text(problem.text) },
            confirmButton = {
                TextButton(onClick = {
                    accountProblem = null
                    fix(problem)
                }) { Text(problem.button) }
            },
            dismissButton = { TextButton(onClick = { accountProblem = null }) { Text("Zavřít") } },
        )
    }

    removing?.let { calendar ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(if (calendar.local) "Smazat kalendář?" else "Přestat synchronizovat?") },
            text = {
                Text(
                    if (calendar.local) "Kalendář „${calendar.name}“ se smaže i se všemi událostmi. Nejde to vrátit."
                    else "Kalendář „${calendar.name}“ zmizí z telefonu, v účtu ${calendar.account} zůstane. " +
                        "Zpět ho zapneš zaškrtnutím v menu.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (calendar.local) repository.deleteLocalCalendar(calendar) else repository.stopSyncing(calendar)
                        }
                        changes++
                    }
                }) { Text(if (calendar.local) "Smazat" else "Přestat") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Zrušit") } },
        )
    }

    peek?.let { (day, list) ->
        DayPeek(
            day = day,
            events = list,
            onEvent = { event ->
                peek = null
                context.startActivity(openEventIntent(context, event))
            },
            onAdd = {
                peek = null
                context.startActivity(newEventIntent(context, day))
            },
            onWholeDay = {
                peek = null
                context.startActivity(dayIntent(context, day))
            },
            onDismiss = { peek = null },
        )
    }

    if (pickMonth) {
        MonthPicker(
            shown = monthForPage(pagerState.currentPage),
            range = monthForPage(0)..monthForPage(pagerState.pageCount - 1),
            onPick = { month ->
                pickMonth = false
                scope.launch { pagerState.scrollToPage(pageForDay(month.atDay(1))) }
            },
            onDismiss = { pickMonth = false },
        )
    }

    if (newCalendar) {
        NewCalendarDialog(
            onDismiss = { newCalendar = false },
            onCreate = { name, color ->
                newCalendar = false
                scope.launch {
                    withContext(Dispatchers.IO) { repository.createLocalCalendar(name, color) }
                    changes++
                }
            },
        )
    }
}

// Big month name with the year next to it, a tap picks another month or year
@Composable
private fun MonthTitle(month: YearMonth, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClickLabel = "Vybrat měsíc a rok", onClick = onClick),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            month.format(DateTimeFormatter.ofPattern("LLLL", czech)).capitalized(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${month.year} ▾",
            Modifier.padding(bottom = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Today's date in a small box, jumps back to the current month
@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Dnes" }) {
        Box(
            Modifier.size(26.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(today.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        weekdays.forEachIndexed { index, name ->
            Text(
                name,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = weekendColor(DayOfWeek.of(index + 1)) ?: MaterialTheme.colorScheme.onSurfaceVariant,
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
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .background(if (picked) pickedColor else Color.Transparent)
            // Thin line between days, like a paper calendar
            .drawBehind { drawLine(gridColor, Offset(size.width, 0f), Offset(size.width, size.height), 0.5.dp.toPx()) }
            .alpha(if (inMonth) 1f else 0.4f),
    ) {
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
            else -> weekendColor(day.dayOfWeek) ?: colors.onSurface
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
                if (filled) color else color.copy(alpha = 0.15f),
                RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
            )
            // Timed events get a colour stripe on the left, all-day ones are filled
            .drawBehind { if (!filled) drawRect(color, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = if (filled) 2.dp else 4.dp, end = 2.dp),
        style = chipStyle,
        color = eventTextColor(event, MaterialTheme.colorScheme.onSurface),
        // Cut at the cell edge instead of wrapping, so "! Zubař" does not shrink to "!"
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}
