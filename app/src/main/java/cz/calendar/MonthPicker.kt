package cz.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle

// Jump to any month: arrows change the year, a tap on a month opens it
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthPicker(shown: YearMonth, range: ClosedRange<YearMonth>, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(shown.year) }
    val now = YearMonth.now()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }, enabled = year > range.start.year) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Předchozí rok")
                }
                Text(
                    year.toString(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { year++ }, enabled = year < range.endInclusive.year) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Další rok")
                }
            }
            Month.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { month ->
                        val item = YearMonth.of(year, month)
                        MonthCell(
                            name = month.getDisplayName(TextStyle.FULL_STANDALONE, czech).capitalized(),
                            selected = item == shown,
                            current = item == now,
                            enabled = item in range,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(item) },
                        )
                    }
                }
            }
            TextButton(onClick = { onPick(now) }, Modifier.align(Alignment.CenterHorizontally)) {
                Text("Dnešní měsíc")
            }
        }
    }
}

@Composable
private fun MonthCell(name: String, selected: Boolean, current: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .padding(vertical = 4.dp)
            .height(48.dp)
            .background(if (selected) colors.primary else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = when {
                selected -> colors.onPrimary
                !enabled -> colors.onSurface.copy(alpha = 0.38f)
                current -> colors.primary
                else -> colors.onSurface
            },
            fontWeight = if (current || selected) FontWeight.Bold else null,
        )
    }
}
