package com.example.conexiuniclujwearos.logic

import com.example.conexiuniclujwearos.data.DaySchedule
import com.example.conexiuniclujwearos.data.OUTGOING_SUFFIX
import com.example.conexiuniclujwearos.data.StopInfo
import com.example.conexiuniclujwearos.data.StopTime
import com.example.conexiuniclujwearos.data.Timetable
import com.example.conexiuniclujwearos.data.routeIdFromTripId
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

// Ports of frontend/src/utils/time.ts and the trip helpers in utils/trips.ts.

// Timetables are in Cluj time, whatever zone the device is set to.
val CLUJ_ZONE: ZoneId = ZoneId.of("Europe/Bucharest")

// Like JS parseInt: leading digits count, anything after them is ignored.
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

/** "now", "7m", or the clock time once it is an hour or more away. */
fun formatMinutesFromNow(rawMinutes: Int, reference: LocalDateTime, nowLabel: String): String {
    val minutes = rawMinutes.toDouble().roundToInt()
    if (minutes == 0) return nowLabel
    if (minutes < 60) return "${minutes}m"
    val future = reference.plusMinutes(minutes.toLong())
    return "%02d:%02d".format(future.hour, future.minute)
}

fun daySchedule(timetable: Timetable, date: LocalDateTime): DaySchedule = when (date.dayOfWeek) {
    DayOfWeek.SUNDAY -> timetable.sunday
    DayOfWeek.SATURDAY -> timetable.saturday
    else -> timetable.weekdays
}

fun hasTimetableEntries(timetable: Timetable?): Boolean =
    timetable != null && (timetable.weekdays.entries.isNotEmpty() ||
        timetable.saturday.entries.isNotEmpty() ||
        timetable.sunday.entries.isNotEmpty())

fun reverseRouteLongName(name: String): String = name.split(" - ").reversed().joinToString(" - ")

fun tripIdForRouteAtStop(outgoing: List<String>, incoming: List<String>, routeId: Int): String? =
    (outgoing + incoming).firstOrNull { routeIdFromTripId(it) == routeId }

/** Minutes from the trip's departure until it reaches [stopId], the stop's own ride included. */
fun timeOffsetToStop(stopTimes: List<StopTime>, tripId: String, stopId: Int): Int {
    var seconds = 0.0
    for (st in stopTimes.filter { it.tripId == tripId }.sortedBy { it.stopSequence }) {
        seconds += st.offsetArrivalTime
        if (st.stopId == stopId) break
    }
    return ceil(seconds / 60).toInt()
}

fun departureMinutes(schedule: DaySchedule, outgoing: Boolean): List<Int> =
    schedule.entries.mapNotNull { timeStringToMinutes(if (outgoing) it.departureIn else it.departureOut) }

data class StopDeparture(
    val routeId: Int,
    val shortName: String,
    val color: String,
    val tripId: String,
    // Already turned around for the incoming direction.
    val longName: String,
    val nextTimes: List<Arrival>,
    val minutesLeft: Int,
    val isStatic: Boolean,
)

/** Timetable-only departures at a stop, soonest first. Port of getAvailableBusesForStop. */
fun availableBusesForStop(
    info: StopInfo,
    now: LocalDateTime,
    maxMinutes: Int? = null,
    limit: Int? = null,
): List<StopDeparture> {
    val nowMinutes = minutesOfDay(now)
    val results = mutableListOf<StopDeparture>()
    for (shape in info.shapesInfo) {
        val schedule = daySchedule(shape.timetable, now)
        if (schedule.entries.isEmpty()) continue
        val tripId = tripIdForRouteAtStop(info.outgoingTripIds, info.incomingTripIds, shape.routeId) ?: continue
        val offset = timeOffsetToStop(shape.stopTimes, tripId, info.stopId)
        val outgoing = tripId.endsWith(OUTGOING_SUFFIX)
        var upcoming = scheduledArrivals(
            departureMinutes(schedule, outgoing), offset, nowMinutes, maxMinutes ?: Int.MAX_VALUE,
        ).map { Arrival(maxOf(it, 0), false) }
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
