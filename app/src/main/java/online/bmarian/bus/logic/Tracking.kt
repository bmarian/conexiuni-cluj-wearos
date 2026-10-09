package online.bmarian.bus.logic

import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.data.StopTime
import online.bmarian.bus.data.Vehicle
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

// Port of frontend/src/composables/useVehicleTracking.ts.

const val TERMINUS_RADIUS_METERS = 200.0
private const val MAX_OFF_ROUTE_METERS = 150.0
private const val VEHICLE_GRACE_PERIOD_MS = 10 * 60_000L
private const val MIN_SPEED_KMH = 7.0
private const val LAYOVER_HIDE_MS = 30 * 60_000L
private const val HEADING_LOOKAHEAD = 3
private const val LIVE_ETA_MAX_POSITION_AGE_MS = 4 * 60_000L
private const val LIVE_ETA_MAX_CLOCK_SKEW_MS = 60_000L
private const val MAX_PLAUSIBLE_SPEED_MPS = 50 / 3.6
private const val MAX_FIX_AGE_CREDIT_SEC = 90.0

class ShapeIndex(val shape: List<ShapePoint>, val cumulativeDist: DoubleArray)

data class IndexedVehicle(
    val vehicle: Vehicle,
    val routeShortName: String,
    val routeColor: String,
    val heading: Double,
    val shapeIdx: Int,
    val offRouteMeters: Double,
    val atStartTerminus: Boolean,
    val dwellMs: Long,
)

data class StopEta(val vehicle: IndexedVehicle, val etaMinutes: Int, val etaSeconds: Double)

fun parseTimestamp(text: String?): Long? =
    if (text.isNullOrEmpty()) null else runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

private fun hasInvalidCoords(v: Vehicle) =
    v.latitude == 0.0 || v.longitude == 0.0 || v.latitude < 0 || v.longitude < 0

private fun isStale(v: Vehicle, now: Long): Boolean {
    val ts = parseTimestamp(v.timestamp) ?: return true
    return now - ts > VEHICLE_GRACE_PERIOD_MS
}

private fun isFreshForLiveEta(v: Vehicle, now: Long): Boolean {
    val ts = parseTimestamp(v.timestamp) ?: return false
    val age = now - ts
    return age >= -LIVE_ETA_MAX_CLOCK_SKEW_MS && age <= LIVE_ETA_MAX_POSITION_AGE_MS
}

private fun dwellMs(v: Vehicle, now: Long): Long {
    val since = parseTimestamp(v.stationarySince) ?: return 0
    return max(0, now - since)
}

private fun isTracked(v: Vehicle, now: Long) =
    !hasInvalidCoords(v) && !isStale(v, now) && dwellMs(v, now) < LAYOVER_HIDE_MS

private fun computeHeading(lat: Double, lon: Double, shape: List<ShapePoint>, shapeIdx: Int): Double {
    if (shapeIdx < 0 || shape.isEmpty()) return 0.0
    val targetIdx = min(shapeIdx + HEADING_LOOKAHEAD, shape.size - 1)
    val target = shape[targetIdx]
    if (targetIdx == shapeIdx || haversineMeters(lat, lon, target.lat, target.lon) < 2) {
        if (shapeIdx > 0) {
            val prev = shape[shapeIdx - 1]
            val curr = shape[shapeIdx]
            return bearingDegrees(prev.lat, prev.lon, curr.lat, curr.lon)
        }
        if (shape.size > 1) return bearingDegrees(shape[0].lat, shape[0].lon, shape[1].lat, shape[1].lon)
    }
    return bearingDegrees(lat, lon, target.lat, target.lon)
}

fun distanceOnShape(index: ShapeIndex, fromShapeIdx: Int, toShapeIdx: Int): Double {
    if (fromShapeIdx < 0 || toShapeIdx < 0 || fromShapeIdx > toShapeIdx) return 0.0
    return index.cumulativeDist[toShapeIdx] - index.cumulativeDist[fromShapeIdx]
}

private fun estimateEtaSeconds(distanceMeters: Double, speedKmh: Double): Double =
    distanceMeters / 1000 / max(speedKmh, MIN_SPEED_KMH) * 3600

fun buildShapeIndex(shape: List<ShapePoint>): ShapeIndex {
    val cumulative = DoubleArray(shape.size)
    for (i in 1 until shape.size) {
        val a = shape[i - 1]
        val b = shape[i]
        cumulative[i] = cumulative[i - 1] + haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }
    return ShapeIndex(shape, cumulative)
}

private class Closest(val idx: Int, val distanceMeters: Double)

private fun closestShapePoint(lat: Double, lon: Double, shape: List<ShapePoint>): Closest {
    var idx = -1
    var distance = Double.POSITIVE_INFINITY
    for (i in shape.indices) {
        val p = shape[i]
        val d = haversineMeters(p.lat, p.lon, lat, lon)
        if (d < distance) {
            distance = d
            idx = i
        }
    }
    return Closest(idx, distance)
}

fun findClosestShapeIdx(lat: Double, lon: Double, shape: List<ShapePoint>): Int =
    closestShapePoint(lat, lon, shape).idx

fun buildStopShapeIdxByStopId(tripStops: List<StopTime>, shape: List<ShapePoint>): Map<Int, Int> {
    val result = HashMap<Int, Int>()
    for (st in tripStops) {
        if (st.lat == 0.0 || st.lon == 0.0) continue
        result[st.stopId] = findClosestShapeIdx(st.lat, st.lon, shape)
    }
    return result
}

private fun stopShapePositions(tripStops: List<StopTime>, shape: List<ShapePoint>): IntArray {
    var last = 0
    return IntArray(tripStops.size) { i ->
        val st = tripStops[i]
        val idx = if (st.lat != 0.0 && st.lon != 0.0) findClosestShapeIdx(st.lat, st.lon, shape) else last
        last = max(last, idx)
        last
    }
}

fun indexVehicles(
    routeShortName: String,
    routeColor: String,
    index: ShapeIndex,
    now: Long,
    raw: List<Vehicle>,
): List<IndexedVehicle> {
    val shape = index.shape
    if (shape.isEmpty()) return emptyList()
    val start = shape[0]
    return raw.filter { isTracked(it, now) }.map { v ->
        val closest = closestShapePoint(v.latitude, v.longitude, shape)
        IndexedVehicle(
            vehicle = v,
            routeShortName = routeShortName,
            routeColor = routeColor,
            heading = computeHeading(v.latitude, v.longitude, shape, closest.idx),
            shapeIdx = closest.idx,
            offRouteMeters = closest.distanceMeters,
            atStartTerminus = haversineMeters(v.latitude, v.longitude, start.lat, start.lon) <= TERMINUS_RADIUS_METERS,
            dwellMs = dwellMs(v, now),
        )
    }
}

fun isOnRoute(v: IndexedVehicle) = v.shapeIdx >= 0 && v.offRouteMeters <= MAX_OFF_ROUTE_METERS

private fun firstStopShapeIdx(shape: List<ShapePoint>, sortedStops: List<StopTime>): Int {
    val first = sortedStops.getOrNull(1)
    if (first == null || first.lat == 0.0 || first.lon == 0.0) return Int.MAX_VALUE
    return findClosestShapeIdx(first.lat, first.lon, shape)
}

// Cached: a route screen asks once per stop with the same lists.
private class PreparedTrip(val stops: List<StopTime>, val positions: IntArray, val firstStopIdx: Int)

private val lastPrepared = ThreadLocal<Triple<List<StopTime>, List<ShapePoint>, PreparedTrip>>()

private fun prepare(tripStops: List<StopTime>, shape: List<ShapePoint>): PreparedTrip {
    lastPrepared.get()?.let { (stops, points, prepared) -> if (stops === tripStops && points === shape) return prepared }
    val sorted = tripStops.sortedBy { it.stopSequence }
    val prepared = PreparedTrip(sorted, stopShapePositions(sorted, shape), firstStopShapeIdx(shape, sorted))
    lastPrepared.set(Triple(tripStops, shape, prepared))
    return prepared
}

private fun canDriveLiveEta(v: IndexedVehicle, now: Long, firstStopIdx: Int): Boolean {
    val waitingAtTerminus = v.atStartTerminus && v.shapeIdx <= firstStopIdx
    return isOnRoute(v) && !waitingAtTerminus && isFreshForLiveEta(v.vehicle, now)
}

fun etaForStop(
    stopShapeIdx: Int,
    vehicles: List<IndexedVehicle>,
    index: ShapeIndex,
    tripStops: List<StopTime>,
    targetStopId: Int,
    now: Long,
): StopEta? {
    if (stopShapeIdx < 0) return null
    val prepared = prepare(tripStops, index.shape)
    val firstStopIdx = prepared.firstStopIdx
    val candidate = vehicles
        .filter { it.shapeIdx <= stopShapeIdx && canDriveLiveEta(it, now, firstStopIdx) }
        .sortedByDescending { it.shapeIdx }
        .firstOrNull() ?: return null
    val seconds = vehicleEtaSeconds(candidate, stopShapeIdx, index, prepared, targetStopId, now)
    return StopEta(candidate, max(0, Math.round(seconds / 60).toInt()), seconds)
}

// Not aged: between fixes there's no evidence the bus moved.
private fun vehicleEtaSeconds(
    vehicle: IndexedVehicle,
    stopShapeIdx: Int,
    index: ShapeIndex,
    trip: PreparedTrip,
    targetStopId: Int,
    now: Long,
): Double {
    val estimated = profileAwareEtaSeconds(vehicle, index, trip, targetStopId, now)
        ?: estimateEtaSeconds(distanceOnShape(index, vehicle.shapeIdx, stopShapeIdx), vehicle.vehicle.speed)
    val remainingMeters = distanceOnShape(index, vehicle.shapeIdx, stopShapeIdx)
    return max(estimated, remainingMeters / MAX_PLAUSIBLE_SPEED_MPS)
}

private fun profileAwareEtaSeconds(
    vehicle: IndexedVehicle,
    index: ShapeIndex,
    trip: PreparedTrip,
    targetStopId: Int,
    now: Long,
): Double? {
    val stops = trip.stops
    if (stops.isEmpty()) return null
    val targetPos = stops.indexOfFirst { it.stopId == targetStopId }
    if (targetPos <= 0) return null

    val positions = trip.positions
    var prevPos = -1
    for (i in 0 until targetPos) {
        if (positions[i] <= vehicle.shapeIdx) prevPos = i else break
    }
    if (prevPos < 0) return null
    val nextPos = prevPos + 1
    if (nextPos > targetPos) return null

    val segmentStartIdx = positions[prevPos]
    val segmentEndIdx = positions[nextPos]
    val segmentMeters = distanceOnShape(index, segmentStartIdx, segmentEndIdx)
    val remainingInSegment = distanceOnShape(index, max(vehicle.shapeIdx, segmentStartIdx), segmentEndIdx)
    val share = if (segmentMeters > 0) (remainingInSegment / segmentMeters).coerceIn(0.0, 1.0) else 0.0

    var profileSeconds = 0.0
    for (pos in nextPos..targetPos) {
        profileSeconds += stops[pos].offsetArrivalTime * (if (pos == nextPos) share else 1.0)
    }
    if (profileSeconds <= 0) return null

    val timestamp = parseTimestamp(vehicle.vehicle.timestamp) ?: return null
    val fixAgeSec = ((now - timestamp) / 1000.0).coerceIn(0.0, MAX_FIX_AGE_CREDIT_SEC)
    return max(0.0, profileSeconds - fixAgeSec)
}
