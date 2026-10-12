package online.bmarian.bus.logic

import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.StopTime
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.routeIdFromTripId
import java.time.LocalDateTime
import kotlin.math.ceil

const val DEPARTURES_SHOWN = 3
private const val STOP_HORIZON_MIN = 480
const val SCHEDULE_HORIZON_MIN = 480

/** The trip whose buses continue as [tripId] at its first stop, when the route runs through there. */
fun feederTripFor(info: StopInfo, tripId: String): String? {
    val routeId = routeIdFromTripId(tripId) ?: return null
    val shape = info.shapesInfo.firstOrNull { it.routeId == routeId } ?: return null
    val turnaround = turnaroundOf(shape.stopTimes.filter { it.tripId == tripId }) ?: return null
    return if (turnaround.through) otherDirectionTripId(tripId) else null
}

fun trackableTripIds(info: StopInfo): List<String> {
    val routeIds = info.shapesInfo.filter { hasTimetableEntries(it.timetable) }.map { it.routeId }.toSet()
    val here = (info.outgoingTripIds + info.incomingTripIds).filter { routeIdFromTripId(it) in routeIds }
    return (here + here.mapNotNull { feederTripFor(info, it) }).distinct()
}

private fun withArrivals(departure: StopDeparture, liveMinutes: List<Int>, tracked: Boolean): StopDeparture? {
    val merged = mergeArrivals(liveMinutes, departure.nextTimes.map { it.minutes }, tracked, DEPARTURES_SHOWN)
    if (merged.isEmpty()) return null
    return departure.copy(nextTimes = merged, minutesLeft = merged.first().minutes, isStatic = liveMinutes.isEmpty())
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
    fun push(departure: StopDeparture, live: List<Int>, tracked: Boolean) {
        withArrivals(departure, live, tracked)?.let { results += it }
    }
    val shapeInfoByRoute = info.shapesInfo.associateBy { it.routeId }

    for (departure in coming) {
        val trip = shapesByTrip[departure.tripId].orEmpty()
        if (trip.isEmpty()) {
            push(departure, emptyList(), false)
            continue
        }
        val shapeIndex = buildShapeIndex(trip)
        val vehicles = indexVehicles(
            departure.shortName, departure.color, shapeIndex, nowMs, vehiclesByTrip[departure.tripId].orEmpty(),
        )
        val tracked = vehicles.isNotEmpty()
        val routeShapeInfo = shapeInfoByRoute[departure.routeId]
        if (routeShapeInfo == null) {
            push(departure, emptyList(), tracked)
            continue
        }
        val tripStops = routeShapeInfo.stopTimes.filter { it.tripId == departure.tripId }
        val stopShapeIdx = buildStopShapeIdxByStopId(tripStops, trip)[info.stopId] ?: -1
        if (stopShapeIdx < 0) {
            push(departure, emptyList(), tracked)
            continue
        }
        var throughRun: ThroughRunOptions? = null
        val turnaround = turnaroundOf(tripStops)
        if (turnaround != null && turnaround.through) {
            val feederTripId = otherDirectionTripId(departure.tripId)
            val feederShape = shapesByTrip[feederTripId].orEmpty()
            val feeder = if (feederShape.isEmpty()) null else {
                val feederIndex = buildShapeIndex(feederShape)
                FeederTrip(
                    indexVehicles(departure.shortName, departure.color, feederIndex, nowMs, vehiclesByTrip[feederTripId].orEmpty()),
                    feederIndex,
                    routeShapeInfo.stopTimes.filter { it.tripId == feederTripId },
                )
            }
            val outgoing = !departure.tripId.endsWith("_1")
            val day = daySchedule(routeShapeInfo.timetable, now)
            throughRun = ThroughRunOptions(
                turnaround, departureMinutes(day, outgoing), minutesOfDay(now), departureMinutes(day, !outgoing), feeder,
            )
        }
        val etas = liveEtasForStop(stopShapeIdx, vehicles, shapeIndex, tripStops, info.stopId, nowMs, throughRun)
        push(departure, etas.map { it.etaMinutes }, tracked)
    }
    return results.sortedBy { it.minutesLeft }
}

class IndexedStop(val stop: StopTime, val offsetFromStart: Int)

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

/** The other direction's buses, which continue as this direction when it runs through. */
fun feederFor(shapeInfo: ShapeInfo, tripId: String, feederShape: DirectionShape?, feederVehicles: List<IndexedVehicle>): FeederTrip? {
    if (feederShape == null) return null
    val feederTripId = otherDirectionTripId(tripId)
    return FeederTrip(feederVehicles, feederShape.index, shapeInfo.stopTimes.filter { it.tripId == feederTripId })
}

private fun throughRunFor(
    shapeInfo: ShapeInfo,
    stops: List<IndexedStop>,
    outgoing: Boolean,
    feeder: FeederTrip?,
    now: LocalDateTime,
): ThroughRunOptions? {
    val turnaround = turnaroundOf(stops.map { it.stop }) ?: return null
    if (!turnaround.through) return null
    val day = daySchedule(shapeInfo.timetable, now)
    return ThroughRunOptions(turnaround, departureMinutes(day, outgoing), minutesOfDay(now), departureMinutes(day, !outgoing), feeder)
}

private fun liveMinutesAt(
    stop: IndexedStop,
    tripStops: List<StopTime>,
    dirShape: DirectionShape?,
    vehicles: List<IndexedVehicle>,
    throughRun: ThroughRunOptions?,
    nowMs: Long,
): List<Int> {
    val stopIdx = dirShape?.stopShapeIdxByStopId?.get(stop.stop.stopId)
    if (dirShape == null || stopIdx == null || stopIdx < 0) return emptyList()
    return liveEtasForStop(stopIdx, vehicles, dirShape.index, tripStops, stop.stop.stopId, nowMs, throughRun).map { it.etaMinutes }
}

/** Port of stopTimesByStop in useRouteArrivals. */
fun routeStopArrivals(
    shapeInfo: ShapeInfo,
    stops: List<IndexedStop>,
    outgoing: Boolean,
    dirShape: DirectionShape?,
    vehicles: List<IndexedVehicle>,
    now: LocalDateTime,
    nowMs: Long,
    feeder: FeederTrip? = null,
): List<List<MarkedArrival>> {
    val departures = departureMinutes(daySchedule(shapeInfo.timetable, now), outgoing)
    val nowMinutes = minutesOfDay(now)
    val tripStops = stops.map { it.stop }
    val tracked = vehicles.isNotEmpty()
    val throughRun = throughRunFor(shapeInfo, stops, outgoing, feeder, now)
    val rows = stops.map { stop ->
        val live = liveMinutesAt(stop, tripStops, dirShape, vehicles, throughRun, nowMs)
        val scheduled = scheduledArrivals(departures, stop.offsetFromStart, nowMinutes, SCHEDULE_HORIZON_MIN)
        mergeArrivals(live, scheduled, tracked)
    }
    return markRepeatedNow(rows)
}

/** Port of getHeaderTimes in useRouteArrivals: terminus departures, live only when a bus runs through. */
fun routeHeaderArrivals(
    shapeInfo: ShapeInfo,
    stops: List<IndexedStop>,
    outgoing: Boolean,
    dirShape: DirectionShape?,
    vehicles: List<IndexedVehicle>,
    now: LocalDateTime,
    nowMs: Long,
    feeder: FeederTrip? = null,
): List<Arrival> {
    val departures = departureMinutes(daySchedule(shapeInfo.timetable, now), outgoing)
    val first = stops.firstOrNull()
    val throughRun = throughRunFor(shapeInfo, stops, outgoing, feeder, now)
    val live = if (first != null && throughRun != null) liveMinutesAt(first, stops.map { it.stop }, dirShape, vehicles, throughRun, nowMs) else emptyList()
    return mergeArrivals(live, scheduledArrivals(departures, first?.offsetFromStart ?: 0, minutesOfDay(now), SCHEDULE_HORIZON_MIN))
}
