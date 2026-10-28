package online.bmarian.bus.logic

import kotlin.math.max
import kotlin.math.roundToInt

// The timetable half of an arrival list; the live half comes from the server.

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

/** Exact minutes until each timetable arrival, soonest first; may start slightly negative. A run up to [graceMinutes] late is still listed. */
fun scheduledArrivals(
    departureMinutes: List<Int>,
    offsetSeconds: Double,
    nowMinutes: Double,
    horizonMinutes: Int,
    graceMinutes: Double,
): List<Double> = departureMinutes
    .map { minutesUntil(it + offsetSeconds / 60, nowMinutes) }
    .filter { it >= -graceMinutes && it < horizonMinutes }
    .sorted()

data class Arrival(val minutes: Int, val isLive: Boolean, val vehicleId: Int = 0)

/** The next runs from the timetable alone, for a list the live feed is not reaching, as the minutes shown. */
fun nextArrivals(scheduled: List<Double>, limit: Int): List<Arrival> =
    scheduled.take(limit).map { Arrival(max(it.roundToInt(), 0), false) }

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
