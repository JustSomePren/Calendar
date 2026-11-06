package cz.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// A spanning event drawn across columns fromCol..toCol on one row
data class WeekBar(
    val event: CalendarEvent,
    val row: Int,
    val fromCol: Int,
    val toCol: Int,
    val startsHere: Boolean,
    val endsHere: Boolean,
)

data class WeekLayout(
    val bars: List<WeekBar>,
    // Per column: row to timed event
    val singles: List<List<Pair<Int, CalendarEvent>>>,
    // Per column: how many events did not fit
    val hidden: List<Int>,
    // Per column: row where "+N" goes
    val moreRow: List<Int>,
)

private fun CalendarEvent.spans() = allDay || multiDay

fun layoutWeek(
    week: List<LocalDate>,
    events: Map<LocalDate, List<CalendarEvent>>,
    slots: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): WeekLayout {
    // Columns of each spanning event, same instance on several days is one bar
    val spanning = LinkedHashMap<Pair<Long, Long>, MutableList<Int>>()
    val byKey = HashMap<Pair<Long, Long>, CalendarEvent>()
    week.forEachIndexed { col, day ->
        events[day].orEmpty().filter { it.spans() }.forEach { event ->
            val key = event.eventId to event.begin
            spanning.getOrPut(key) { mutableListOf() }.add(col)
            byKey[key] = event
        }
    }

    // Longer bars first, then each takes the lowest free row
    val ordered = spanning.entries.sortedWith(compareBy({ it.value.first() }, { -it.value.size }))
    val taken = List(week.size) { mutableSetOf<Int>() }
    val placed = ordered.map { (key, cols) ->
        val row = generateSequence(0) { it + 1 }.first { row -> cols.none { row in taken[it] } }
        cols.forEach { taken[it].add(row) }
        Triple(byKey.getValue(key), row, cols)
    }

    val singles = week.mapIndexed { col, day ->
        val free = generateSequence(0) { it + 1 }.filter { it !in taken[col] }.iterator()
        events[day].orEmpty().filterNot { it.spans() }.map { free.next() to it }
    }

    // When a column overflows, its last visible row shows "+N" instead
    val visibleRows = week.indices.map { col ->
        val used = (taken[col] + singles[col].map { it.first }).maxOrNull()?.plus(1) ?: 0
        if (used <= slots) used else (slots - 1).coerceAtLeast(0)
    }
    val hidden = week.indices.map { col ->
        taken[col].count { it >= visibleRows[col] } + singles[col].count { it.first >= visibleRows[col] }
    }

    val bars = placed.flatMap { (event, row, cols) ->
        cols.filter { row < visibleRows[it] }
            .fold(mutableListOf<MutableList<Int>>()) { runs, col ->
                if (runs.lastOrNull()?.last() == col - 1) runs.last().add(col) else runs.add(mutableListOf(col))
                runs
            }
            .map { run ->
                val days = eventDays(event.begin, event.end, if (event.allDay) ZoneOffset.UTC else zone)
                WeekBar(
                    event = event,
                    row = row,
                    fromCol = run.first(),
                    toCol = run.last(),
                    startsHere = week[run.first()] == days.start,
                    endsHere = week[run.last()] == days.endInclusive,
                )
            }
    }

    return WeekLayout(
        bars = bars,
        singles = singles.mapIndexed { col, list -> list.filter { it.first < visibleRows[col] } },
        hidden = hidden,
        moreRow = visibleRows,
    )
}
