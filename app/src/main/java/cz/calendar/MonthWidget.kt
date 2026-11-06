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
                    .eventsByDay(days.first(), days.last(), Settings(context).hiddenCalendars)
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
        WidgetUpdates.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdates.stop(context)
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
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    week.forEach { day ->
                        WidgetDay(day, YearMonth.from(day) == month, events[day].orEmpty(), slots, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetDay(day: LocalDate, inMonth: Boolean, events: List<CalendarEvent>, slots: Int, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val isToday = day == LocalDate.now()
    val visible = events.fitInto(slots)

    Column(
        modifier
            .fillMaxHeight()
            .padding(1.dp)
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
        visible.forEach { event ->
            val color = Color(event.color)
            val filled = event.allDay || event.multiDay
            Text(
                event.title,
                GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
                    .background(ColorProvider(if (filled) color else color.copy(alpha = 0.25f)))
                    .cornerRadius(3.dp)
                    .padding(horizontal = 2.dp),
                style = TextStyle(
                    color = if (filled) ColorProvider(eventTextColor(event, Color.Black)) else colors.onSurface,
                    fontSize = 9.sp,
                ),
                maxLines = 1,
            )
        }
        if (events.size > visible.size) {
            Text("+${events.size - visible.size}", style = TextStyle(color = colors.onSurfaceVariant, fontSize = 9.sp))
        }
    }
}
