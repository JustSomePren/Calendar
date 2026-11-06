package cz.calendar

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val dayKey = ActionParameters.Key<String>(EXTRA_DAY)

class MonthWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val month = YearMonth.now()
        val days = gridDays(month)
        val events = if (context.hasCalendarAccess()) {
            withContext(Dispatchers.IO) {
                CalendarRepository(context.contentResolver)
                    .eventsByDay(days.first(), days.last(), Settings(context).hiddenCalendars, Settings(context).eventsWithPriority(Priority.HIGH))
            }
        } else {
            null
        }

        provideContent {
            GlanceTheme {
                WidgetContent(month, days, events)
            }
        }
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = MonthWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        BackgroundWork.start(context)
    }
}

@Composable
private fun WidgetContent(month: YearMonth, days: List<LocalDate>, events: Map<LocalDate, List<CalendarEvent>>?) {
    val colors = GlanceTheme.colors
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(6.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            monthTitle(month),
            GlanceModifier.padding(start = 6.dp, bottom = 2.dp),
            style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )

        if (events == null) {
            Text(
                "Otevři aplikaci a povol přístup ke kalendářům.",
                GlanceModifier.padding(6.dp),
                style = TextStyle(color = colors.onSurface),
            )
        } else {
            Row(GlanceModifier.fillMaxWidth()) {
                weekdays.forEachIndexed { index, name ->
                    Text(
                        name,
                        GlanceModifier.defaultWeight(),
                        style = TextStyle(
                            color = if (index == 6) colors.error else colors.onSurfaceVariant,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }

            // Title and weekday rows take about 56dp, the rest is split into six weeks
            val slots = (((LocalSize.current.height - 56.dp) / 6 - 20.dp) / 15.dp).toInt()
            days.chunked(7).forEach { week ->
                val layout = layoutWeek(week, events, slots)
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    week.forEachIndexed { col, day ->
                        WidgetDay(day, col, YearMonth.from(day) == month, layout, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetDay(day: LocalDate, col: Int, inMonth: Boolean, layout: WeekLayout, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val isToday = day == LocalDate.now()

    Column(
        modifier
            .fillMaxHeight()
            .padding(vertical = 1.dp)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(dayKey to day.toString()))),
    ) {
        Text(
            day.dayOfMonth.toString(),
            GlanceModifier
                .fillMaxWidth()
                .background(if (isToday) colors.primary else ColorProvider(Color.Transparent))
                .cornerRadius(8.dp),
            style = TextStyle(
                color = when {
                    isToday -> colors.onPrimary
                    !inMonth -> colors.outline
                    day.dayOfWeek == DayOfWeek.SUNDAY -> colors.error
                    else -> colors.onSurface
                },
                fontSize = 11.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        // Bars are cut into per-day pieces with no gap, so they still read as one bar
        for (row in 0 until layout.moreRow[col]) {
            val bar = layout.bars.find { it.row == row && col in it.fromCol..it.toCol }
            val single = layout.singles[col].find { it.first == row }?.second
            when {
                bar != null -> WidgetChip(bar.event, if (col == bar.fromCol) bar.event.label else "", bar.startsHere || col != bar.fromCol, col == bar.toCol)
                single != null -> WidgetChip(single, single.label, padStart = true, padEnd = true)
                else -> Spacer(GlanceModifier.height(15.dp))
            }
        }
        if (layout.hidden[col] > 0) {
            Text("+${layout.hidden[col]}", style = TextStyle(color = colors.onSurfaceVariant, fontSize = 9.sp))
        }
    }
}

@Composable
private fun WidgetChip(event: CalendarEvent, text: String, padStart: Boolean, padEnd: Boolean) {
    val color = Color(event.color)
    val filled = event.allDay || event.multiDay
    Text(
        text,
        GlanceModifier
            .fillMaxWidth()
            .height(15.dp)
            .padding(start = if (padStart) 1.dp else 0.dp, end = if (padEnd) 1.dp else 0.dp, top = 1.dp)
            .background(ColorProvider(if (filled) color else color.copy(alpha = 0.25f)))
            .padding(horizontal = 2.dp),
        style = TextStyle(
            color = if (filled) ColorProvider(eventTextColor(event, Color.Black)) else GlanceTheme.colors.onSurface,
            fontSize = 9.sp,
        ),
        maxLines = 1,
    )
}
