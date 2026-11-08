package cz.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

// Days with this many events or fewer open in a small sheet, busier ones on a full page
const val PEEK_LIMIT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPeek(
    day: LocalDate,
    events: List<CalendarEvent>,
    onEvent: (CalendarEvent) -> Unit,
    onAdd: () -> Unit,
    onWholeDay: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(day.format(dayTitleFormat).capitalized(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (events.isEmpty()) {
                Text(
                    "Žádné události",
                    Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            events.forEach { event -> EventRow(event, onClick = { onEvent(event) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onWholeDay) { Text("Celý den") }
                TextButton(onClick = onAdd) { Text("Přidat událost") }
            }
        }
    }
}
