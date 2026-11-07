package cz.calendar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun VacationSection(vacation: Vacation?, title: String, accounts: List<String>, onChange: (Vacation?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dovolená / výlet", Modifier.weight(1f))
        Switch(vacation != null, onCheckedChange = { onChange(if (it) Vacation(album = "") else null) })
    }
    if (vacation == null) {
        Hint("Galerie z fotek těchto dní udělá album a může ho poslat do Google Fotek.")
        return
    }
    OutlinedTextField(
        value = vacation.album,
        onValueChange = { onChange(vacation.copy(album = it)) },
        label = { Text("Album") },
        placeholder = { Text(title.ifBlank { "Podle názvu" }) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Posílat do Google Fotek", Modifier.weight(1f))
        Switch(
            vacation.autoSend,
            enabled = accounts.isNotEmpty(),
            onCheckedChange = { onChange(vacation.copy(autoSend = it, account = vacation.account ?: accounts.firstOrNull())) },
        )
    }
    if (accounts.isEmpty()) {
        Hint("Google účet přidáš v Galerii → Odesílání → Google účty.")
    } else if (vacation.autoSend) {
        ChoiceRow("Účet", accounts.map { it to it }, vacation.account ?: accounts.first(), enabled = true) {
            onChange(vacation.copy(account = it))
        }
        Hint("Galerie pošle fotky z těchto dní samy na Wi-Fi, bez snímků obrazovky.")
    }
}

// count is null when the gallery did not answer
private class Found(val count: Int?, val thumbs: List<Bitmap>, val place: Pair<Double, Double>?)

// Photos the gallery paired with the event, plus where they were taken
@Composable
fun EventPhotos(draft: EventDraft, onUseLocation: ((String) -> Unit)?) {
    val context = LocalContext.current
    val window = GalleryLink.window(draft)
    if (window.first > System.currentTimeMillis()) return

    var found by remember { mutableStateOf<Found?>(null) }
    LaunchedEffect(window) {
        found = withContext(Dispatchers.IO) {
            val ids = GalleryLink.photos(context, window)
            val place = if (ids.isNullOrEmpty()) null else GalleryLink.place(context, window)
            Found(ids?.size, ids.orEmpty().take(10).mapNotNull { GalleryLink.thumbnail(context, it) }, place)
        }
    }
    SectionTitle("Fotky" + (found?.count?.takeIf { it > 0 }?.let { " ($it)" } ?: ""))
    val photos = found
    when {
        photos == null -> Hint("Hledám fotky v Galerii…")
        photos.count == null -> Hint("Galerie fotky nepředala. Otevři jednou Galerii a povol jí přístup k fotkám.")
        photos.count == 0 -> Hint("Z doby události nejsou žádné fotky (snímky obrazovky se nepočítají).")
    }
    if (photos?.count == null || photos.count == 0) return
    val open = { GalleryLink.open(context, draft.title.ifBlank { "Událost" }, window) }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        photos.thumbs.forEach {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = open),
            )
        }
    }
    TextButton(onClick = open) { Text("Otevřít v Galerii") }

    val (lat, lon) = photos.place ?: return
    val coordinates = "%.5f, %.5f".format(Locale.US, lat, lon)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Místo podle fotek: $coordinates", Modifier.weight(1f))
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }
        }) { Text("Na mapě") }
        if (onUseLocation != null) TextButton(onClick = { onUseLocation(coordinates) }) { Text("Použít") }
    }
}

// Photo counts of the events that already started, empty without the gallery
fun photoCounts(context: Context, events: List<CalendarEvent>): Map<CalendarEvent, Int> {
    if (!GalleryLink.installed(context)) return emptyMap()
    val settings = Settings(context)
    val past = events.filter { it.begin <= System.currentTimeMillis() }
    val counts = GalleryLink.counts(context, past.map { GalleryLink.window(it, settings.vacation(it.eventId) != null) })
    return past.zip(counts.orEmpty()).toMap().filterValues { it > 0 }
}

fun photosLabel(count: Int) = when {
    count == 1 -> "1 fotka"
    count in 2..4 -> "$count fotky"
    else -> "$count fotek"
}
