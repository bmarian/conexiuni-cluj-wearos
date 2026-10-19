package online.bmarian.bus.logic

import online.bmarian.bus.data.DaySchedule
import online.bmarian.bus.data.OUTGOING_SUFFIX
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.StopTime
import online.bmarian.bus.data.Timetable
import online.bmarian.bus.data.routeIdFromTripId
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

// Ports of frontend/src/utils/time.ts and utils/trips.ts.

val CLUJ_ZONE: ZoneId = ZoneId.of("Europe/Bucharest")

// JS parseInt.
private fun parseLeadingInt(text: String): Int? {
    val trimmed = text.trimStart()
    var end = 0
    if (end < trimmed.length && (trimmed[end] == '-' || trimmed[end] == '+')) end++
    while (end < trimmed.length && trimmed[end].isDigit()) end++
    return trimmed.substring(0, end).toIntOrNull()
}

fun timeStringToMinutes(time: String): Int? {
    if (time.isEmpty() || !time.contains(':')) return null
    val parts = time.trim().split(':')
    val hours = parseLeadingInt(parts[0]) ?: return null
    val minutes = parseLeadingInt(parts.getOrElse(1) { "" }) ?: return null
    return hours * 60 + minutes
}

fun minutesOfDay(time: LocalDateTime): Int = time.hour * 60 + time.minute

/** Minutes past midnight with the seconds, for arithmetic that is rounded only when shown. */
fun exactMinutesOfDay(time: LocalDateTime): Double = time.hour * 60 + time.minute + time.second / 60.0

fun formatMinutesFromNow(rawMinutes: Int, reference: LocalDateTime, nowLabel: String): String {
    val minutes = rawMinutes.toDouble().roundToInt()
    if (minutes == 0) return nowLabel
    if (minutes in 1..59) return "${minutes}m"
    val future = reference.plusMinutes(minutes.toLong())
    return "%02d:%02d".format(future.hour, future.minute)
}

fun daySchedule(timetable: Timetable, date: LocalDateTime): DaySchedule = when (scheduleDayOn(date.toLocalDate())) {
    "sunday" -> timetable.sunday
    "saturday" -> timetable.saturday
    else -> timetable.weekdays
}

fun hasTimetableEntries(timetable: Timetable?): Boolean =
    timetable != null && (timetable.weekdays.entries.isNotEmpty() ||
        timetable.saturday.entries.isNotEmpty() ||
        timetable.sunday.entries.isNotEmpty())

fun reverseRouteLongName(name: String): String = name.split(" - ").reversed().joinToString(" - ")

fun tripIdForRouteAtStop(outgoing: List<String>, incoming: List<String>, routeId: Int): String? =
    (outgoing + incoming).firstOrNull { routeIdFromTripId(it) == routeId }

fun timeOffsetToStop(stopTimes: List<StopTime>, tripId: String, stopId: Int): Double {
    var seconds = 0.0
    for (st in stopTimes.filter { it.tripId == tripId }.sortedBy { it.stopSequence }) {
        seconds += st.offsetArrivalTime
        if (st.stopId == stopId) break
    }
    return seconds
}

fun departureMinutes(schedule: DaySchedule, outgoing: Boolean): List<Int> =
    schedule.entries.mapNotNull { timeStringToMinutes(if (outgoing) it.departureIn else it.departureOut) }

data class StopDeparture(
    val routeId: Int,
    val shortName: String,
    val color: String,
    val tripId: String,
    val longName: String,
    val nextTimes: List<Arrival>,
    val minutesLeft: Int,
    val isStatic: Boolean,
    // The platform it calls at, when a station has several.
    val stopId: Int = 0,
)

/** Port of getAvailableBusesForStop. */
fun availableBusesForStop(
    info: StopInfo,
    now: LocalDateTime,
    maxMinutes: Int? = null,
    limit: Int? = null,
): List<StopDeparture> {
    val nowMinutes = exactMinutesOfDay(now)
    val results = mutableListOf<StopDeparture>()
    for (shape in info.shapesInfo) {
        val schedule = daySchedule(shape.timetable, now)
        if (schedule.entries.isEmpty()) continue
        val tripId = tripIdForRouteAtStop(info.outgoingTripIds, info.incomingTripIds, shape.routeId) ?: continue
        val offset = timeOffsetToStop(shape.stopTimes, tripId, info.stopId)
        val outgoing = tripId.endsWith(OUTGOING_SUFFIX)
        var upcoming = scheduledArrivals(
            departureMinutes(schedule, outgoing), offset, nowMinutes, maxMinutes ?: Int.MAX_VALUE, shape.lateGraceMin,
        ).map { Arrival(maxOf(it.roundToInt(), 0), false) }
        if (limit != null) upcoming = upcoming.take(limit)
        if (upcoming.isEmpty()) continue
        results += StopDeparture(
            routeId = shape.routeId,
            shortName = shape.shortName,
            color = shape.color,
            tripId = tripId,
            longName = if (outgoing) shape.timetable.routeLongName else reverseRouteLongName(shape.timetable.routeLongName),
            nextTimes = upcoming,
            minutesLeft = upcoming.first().minutes,
            isStatic = true,
        )
    }
    return results.sortedBy { it.minutesLeft }
}
