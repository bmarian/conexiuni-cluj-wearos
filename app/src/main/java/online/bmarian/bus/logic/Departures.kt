package online.bmarian.bus.logic

import online.bmarian.bus.data.LiveState
import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.StopTime
import online.bmarian.bus.data.routeIdFromTripId
import java.time.LocalDateTime

const val DEPARTURES_SHOWN = 3
private const val STOP_HORIZON_MIN = 480
const val SCHEDULE_HORIZON_MIN = 480

fun trackableTripIds(info: StopInfo): List<String> {
    val routeIds = info.shapesInfo.filter { hasTimetableEntries(it.timetable) }.map { it.routeId }.toSet()
    return (info.outgoingTripIds + info.incomingTripIds).filter { routeIdFromTripId(it) in routeIds }
}

/** A station's departures from all its platforms; a trip calling at two is listed at the one it reaches first. */
fun stationDepartures(infos: List<StopInfo>, live: LiveState?, now: LocalDateTime, nowMs: Long): List<StopDeparture> =
    infos.flatMap { info -> stopDepartures(info, live, now, nowMs).map { it.copy(stopId = info.stopId) } }
        .groupBy { it.tripId }.values.map { list -> list.minBy { it.minutesLeft } }
        .sortedBy { it.minutesLeft }

/** The stop's departures: the server's rows where the live feed has them, the timetable otherwise. */
fun stopDepartures(info: StopInfo, live: LiveState?, now: LocalDateTime, nowMs: Long): List<StopDeparture> =
    availableBusesForStop(info, now, STOP_HORIZON_MIN, DEPARTURES_SHOWN + 1).mapNotNull { departure ->
        val entries = live?.entriesAt(departure.tripId, info.stopId, nowMs)
        if (entries != null) {
            if (entries.isEmpty()) return@mapNotNull null
            departure.copy(
                nextTimes = entries.map { Arrival(it.minutes, it.live) },
                minutesLeft = entries.first().minutes,
                isStatic = entries.none { it.live },
            )
        } else {
            val merged = departure.nextTimes.take(DEPARTURES_SHOWN)
            if (merged.isEmpty()) return@mapNotNull null
            departure.copy(nextTimes = merged, minutesLeft = merged.first().minutes, isStatic = true)
        }
    }.sortedBy { it.minutesLeft }

class IndexedStop(val stop: StopTime, val secondsFromStart: Double)

fun stopsForDirection(shapeInfo: ShapeInfo, tripId: String): List<IndexedStop> {
    var cumulativeSec = 0.0
    return shapeInfo.stopTimes.filter { it.tripId == tripId }.sortedBy { it.stopSequence }.map {
        cumulativeSec += it.offsetArrivalTime
        IndexedStop(it, cumulativeSec)
    }
}

class DirectionShape(val shape: List<ShapePoint>)

fun directionShape(shape: List<ShapePoint>): DirectionShape? = if (shape.isEmpty()) null else DirectionShape(shape)

private fun scheduledRow(shapeInfo: ShapeInfo, stop: IndexedStop, outgoing: Boolean, now: LocalDateTime): List<Arrival> {
    val departures = departureMinutes(daySchedule(shapeInfo.timetable, now), outgoing)
    return nextArrivals(scheduledArrivals(departures, stop.secondsFromStart, exactMinutesOfDay(now), SCHEDULE_HORIZON_MIN, shapeInfo.lateGraceMin), DEPARTURES_SHOWN)
}

/** The route's stop list: the server's rows where the live feed has them, the timetable otherwise. */
fun routeStopArrivals(
    shapeInfo: ShapeInfo,
    stops: List<IndexedStop>,
    outgoing: Boolean,
    tripId: String,
    live: LiveState?,
    now: LocalDateTime,
    nowMs: Long,
): List<List<MarkedArrival>> {
    val rows = stops.map { live?.entriesAt(tripId, it.stop.stopId, nowMs) }
    if (rows.any { it != null }) {
        return stops.mapIndexed { i, stop ->
            rows[i]?.map { MarkedArrival(it.minutes, it.live, it.soon) }
                ?: scheduledRow(shapeInfo, stop, outgoing, now).map { MarkedArrival(it.minutes, false, false) }
        }
    }
    return markRepeatedNow(stops.map { scheduledRow(shapeInfo, it, outgoing, now) })
}

/** Departures from the terminus: the first stop's row, or the timetable. */
fun routeHeaderArrivals(
    shapeInfo: ShapeInfo,
    stops: List<IndexedStop>,
    outgoing: Boolean,
    tripId: String,
    live: LiveState?,
    now: LocalDateTime,
    nowMs: Long,
): List<Arrival> {
    val first = stops.firstOrNull() ?: return emptyList()
    live?.entriesAt(tripId, first.stop.stopId, nowMs)?.let { entries -> return entries.map { Arrival(it.minutes, it.live) } }
    return scheduledRow(shapeInfo, first, outgoing, now)
}
