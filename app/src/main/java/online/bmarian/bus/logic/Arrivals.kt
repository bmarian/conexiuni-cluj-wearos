package online.bmarian.bus.logic

import kotlin.math.max
import kotlin.math.roundToInt

// Port of frontend/src/utils/arrivals.ts.

/** Wraps around midnight past half a day. */
fun minutesUntil(absMinutes: Int, nowMinutes: Int): Int {
    val diff = absMinutes - nowMinutes
    if (diff < -720) return diff + 1440
    if (diff > 720) return diff - 1440
    return diff
}

fun minutesUntil(absMinutes: Double, nowMinutes: Double): Double {
    val diff = absMinutes - nowMinutes
    if (diff < -720) return diff + 1440
    if (diff > 720) return diff - 1440
    return diff
}

const val SCHEDULE_LATE_GRACE_MIN = 3

/** Soonest first; may start slightly negative. */
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

fun mergeArrivals(
    liveMinutes: Int?,
    scheduled: List<Int>,
    tracked: Boolean = false,
    limit: Int = 3,
): List<Arrival> = mergeArrivals(listOfNotNull(liveMinutes), scheduled, tracked, limit)

/** Each tracked bus first, then only the timetable runs after the last of them. */
fun mergeArrivals(
    liveMinutes: List<Int>,
    scheduled: List<Int>,
    tracked: Boolean = false,
    limit: Int = 3,
): List<Arrival> {
    val live = liveMinutes.map { max(it, 0) }.sorted()
    if (live.isEmpty()) {
        val pending = if (tracked) scheduled.filter { it >= 1 } else scheduled
        return pending.take(limit).map { Arrival(max(it, 0), false) }
    }
    val last = live.last()
    val later = scheduled.filter { it > last + SCHEDULE_LATE_GRACE_MIN }
    return (live.map { Arrival(it, true) } + later.map { Arrival(it, false) }).take(limit)
}

data class MarkedArrival(val minutes: Int, val isLive: Boolean, val soon: Boolean)

private fun readsNow(arrival: Arrival?): Boolean = arrival != null && arrival.minutes.toDouble().roundToInt() == 0

/** The first of consecutive zeros in a column keeps "now"; the rest read "soon". */
fun markRepeatedNow(rows: List<List<Arrival>>): List<List<MarkedArrival>> =
    rows.mapIndexed { stop, row ->
        row.mapIndexed { column, arrival ->
            val soon = readsNow(arrival) &&
                (readsNow(rows.getOrNull(stop - 1)?.getOrNull(column)) || row.take(column).any { readsNow(it) })
            MarkedArrival(arrival.minutes, arrival.isLive, soon)
        }
    }
