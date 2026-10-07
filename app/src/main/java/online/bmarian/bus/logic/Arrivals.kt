package online.bmarian.bus.logic

import kotlin.math.max
import kotlin.math.roundToInt

// Port of frontend/src/utils/arrivals.ts. Keep the two in step.

/** Signed minutes from now until [absMinutes]; differences past half a day cross midnight. */
fun minutesUntil(absMinutes: Int, nowMinutes: Int): Int {
    val diff = absMinutes - nowMinutes
    if (diff < -720) return diff + 1440
    if (diff > 720) return diff - 1440
    return diff
}

// A run this many minutes late is still listed rather than treated as gone.
const val SCHEDULE_LATE_GRACE_MIN = 3

/** Upcoming timetable arrivals at one stop, soonest first. May start slightly negative. */
fun scheduledArrivals(
    departureMinutes: List<Int>,
    offsetMinutes: Int,
    nowMinutes: Int,
    horizonMinutes: Int = Int.MAX_VALUE,
): List<Int> = departureMinutes
    .map { minutesUntil(it + offsetMinutes, nowMinutes) }
    .filter { it >= -SCHEDULE_LATE_GRACE_MIN && it < horizonMinutes }
    .sorted()

data class Arrival(val minutes: Int, val isLive: Boolean)

/**
 * Live estimate and timetable reconciled on time: the tracked bus is one of the scheduled
 * runs, so the slots at or before it come out of the list.
 */
fun mergeArrivals(
    liveMinutes: Int?,
    scheduled: List<Int>,
    tracked: Boolean = false,
    limit: Int = 3,
): List<Arrival> {
    if (liveMinutes == null) {
        val pending = if (tracked) scheduled.filter { it >= 1 } else scheduled
        return pending.take(limit).map { Arrival(max(it, 0), false) }
    }
    val live = max(liveMinutes, 0)
    val later = scheduled.filter { it > live + SCHEDULE_LATE_GRACE_MIN }
    return listOf(Arrival(live, true)) + later.take(max(limit - 1, 0)).map { Arrival(it, false) }
}

data class MarkedArrival(val minutes: Int, val isLive: Boolean, val soon: Boolean)

private fun readsNow(arrival: Arrival?): Boolean = arrival != null && arrival.minutes.toDouble().roundToInt() == 0

/** The first of consecutive zeros down a column keeps "now"; the rest read "soon". */
fun markRepeatedNow(rows: List<List<Arrival>>): List<List<MarkedArrival>> =
    rows.mapIndexed { stop, row ->
        row.mapIndexed { column, arrival ->
            val soon = readsNow(arrival) &&
                (readsNow(rows.getOrNull(stop - 1)?.getOrNull(column)) || row.take(column).any { readsNow(it) })
            MarkedArrival(arrival.minutes, arrival.isLive, soon)
        }
    }
