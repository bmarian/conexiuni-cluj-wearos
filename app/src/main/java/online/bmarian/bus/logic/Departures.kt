package online.bmarian.bus.logic

import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.StopTime
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.routeIdFromTripId
import java.time.LocalDateTime
import kotlin.math.ceil

// Times per departure row. One more is asked of the timetable, because merging in a live
// estimate consumes the slot that same run occupies.
const val DEPARTURES_SHOWN = 3
private const val STOP_HORIZON_MIN = 480
const val SCHEDULE_HORIZON_MIN = 480

/** Trips at the stop whose route has a timetable: what to stream and which shapes to load. */
fun trackableTripIds(info: StopInfo): List<String> {
    val routeIds = info.shapesInfo.filter { hasTimetableEntries(it.timetable) }.map { it.routeId }.toSet()
    return (info.outgoingTripIds + info.incomingTripIds).filter { routeIdFromTripId(it) in routeIds }
}

private fun withArrivals(departure: StopDeparture, liveMinutes: Int?, tracked: Boolean): StopDeparture? {
    val merged = mergeArrivals(liveMinutes, departure.nextTimes.map { it.minutes }, tracked, DEPARTURES_SHOWN)
    if (merged.isEmpty()) return null
    return departure.copy(nextTimes = merged, minutesLeft = merged.first().minutes, isStatic = liveMinutes == null)
}

/** Port of the departure list in useStopDepartures. */
fun computeStopDepartures(
    info: StopInfo,
    shapesByTrip: Map<String, List<ShapePoint>>,
    vehiclesByTrip: Map<String, List<Vehicle>>,
    now: LocalDateTime,
    nowMs: Long,
): List<StopDeparture> {
    val coming = availableBusesForStop(info, now, STOP_HORIZON_MIN, DEPARTURES_SHOWN + 1)
    val results = mutableListOf<StopDeparture>()
    fun push(departure: StopDeparture, live: Int?, tracked: Boolean) {
        withArrivals(departure, live, tracked)?.let { results += it }
    }
    val shapeInfoByRoute = info.shapesInfo.associateBy { it.routeId }

    for (departure in coming) {
        val trip = shapesByTrip[departure.tripId].orEmpty()
        if (trip.isEmpty()) {
            push(departure, null, false)
            continue
        }
        val shapeIndex = buildShapeIndex(trip)
        val vehicles = indexVehicles(
            departure.shortName, departure.color, shapeIndex, nowMs, vehiclesByTrip[departure.tripId].orEmpty(),
        )
        val tracked = vehicles.isNotEmpty()
        val routeShapeInfo = shapeInfoByRoute[departure.routeId]
        if (routeShapeInfo == null) {
            push(departure, null, tracked)
            continue
        }
        val tripStops = routeShapeInfo.stopTimes.filter { it.tripId == departure.tripId }
        val stopShapeIdx = buildStopShapeIdxByStopId(tripStops, trip)[info.stopId] ?: -1
        if (stopShapeIdx < 0) {
            push(departure, null, tracked)
            continue
        }
        val eta = etaForStop(stopShapeIdx, vehicles, shapeIndex, tripStops, info.stopId, nowMs)
        push(departure, eta?.etaMinutes, tracked)
    }
    return results.sortedBy { it.minutesLeft }
}

class IndexedStop(val stop: StopTime, val offsetFromStart: Int)

/** One direction's stops in order, with the minutes each is from the start of the trip. */
fun stopsForDirection(shapeInfo: ShapeInfo, tripId: String): List<IndexedStop> {
    var cumulativeSec = 0.0
    return shapeInfo.stopTimes.filter { it.tripId == tripId }.sortedBy { it.stopSequence }.map {
        cumulativeSec += it.offsetArrivalTime
        IndexedStop(it, ceil(cumulativeSec / 60).toInt())
    }
}

class DirectionShape(
    val shape: List<ShapePoint>,
    val index: ShapeIndex,
    val stopShapeIdxByStopId: Map<Int, Int>,
)

fun directionShape(shapeInfo: ShapeInfo, tripId: String, shape: List<ShapePoint>): DirectionShape? {
    if (shape.isEmpty()) return null
    val tripStops = shapeInfo.stopTimes.filter { it.tripId == tripId }
    return DirectionShape(shape, buildShapeIndex(shape), buildStopShapeIdxByStopId(tripStops, shape))
}

/** Port of stopTimesByStop in useRouteArrivals: next arrivals at every stop of a direction. */
fun routeStopArrivals(
    shapeInfo: ShapeInfo,
    stops: List<IndexedStop>,
    outgoing: Boolean,
    dirShape: DirectionShape?,
    vehicles: List<IndexedVehicle>,
    now: LocalDateTime,
    nowMs: Long,
): List<List<MarkedArrival>> {
    val departures = departureMinutes(daySchedule(shapeInfo.timetable, now), outgoing)
    val nowMinutes = minutesOfDay(now)
    val tripStops = stops.map { it.stop }
    val tracked = vehicles.isNotEmpty()
    val rows = stops.map { stop ->
        val stopIdx = dirShape?.stopShapeIdxByStopId?.get(stop.stop.stopId)
        val live = if (dirShape == null || stopIdx == null || stopIdx < 0) null
        else etaForStop(stopIdx, vehicles, dirShape.index, tripStops, stop.stop.stopId, nowMs)?.etaMinutes
        val scheduled = scheduledArrivals(departures, stop.offsetFromStart, nowMinutes, SCHEDULE_HORIZON_MIN)
        mergeArrivals(live, scheduled, tracked)
    }
    return markRepeatedNow(rows)
}
