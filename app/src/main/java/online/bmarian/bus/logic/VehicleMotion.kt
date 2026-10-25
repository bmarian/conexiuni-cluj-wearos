package online.bmarian.bus.logic

import online.bmarian.bus.data.ShapePoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Port of utils/vehicleMotion.ts.

private const val MAX_OFF_ROUTE_M = 150.0
private const val MAX_MPS = 20.0
private const val ACCEL = 1.2
private const val BRAKE = 2.5
private const val CATCH_UP_S = 30.0
private const val HOLD_BACK_S = 40.0
private const val COAST_S = 8.0
private const val JUMP_M = 400.0
private const val HEADING_SPAN_M = 15.0
private const val BACKTRACK_M = 60.0
private const val TREND_WEIGHT = 0.3
private const val LATE_SHARE = 0.75

class Spot(val s: Double, val off: Double)

class RoutePath(private val lats: DoubleArray, private val lons: DoubleArray, private val at: DoubleArray) {
    val length: Double get() = at.last()

    fun pointAt(s: Double): Pair<Double, Double> {
        var lo = 0
        var hi = at.size - 1
        if (s <= 0 || hi == 0) return lats[0] to lons[0]
        if (s >= at[hi]) return lats[hi] to lons[hi]
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (at[mid] <= s) lo = mid else hi = mid
        }
        val span = at[hi] - at[lo]
        val k = (s - at[lo]) / (if (span == 0.0) 1.0 else span)
        return lats[lo] + (lats[hi] - lats[lo]) * k to lons[lo] + (lons[hi] - lons[lo]) * k
    }

    fun headingAt(s: Double): Double {
        val (fromLat, fromLon) = pointAt(s - HEADING_SPAN_M)
        val (toLat, toLon) = pointAt(s + HEADING_SPAN_M)
        return bearingDegrees(fromLat, fromLon, toLat, toLon)
    }

    fun locate(lat: Double, lon: Double, from: Double = 0.0, to: Double = Double.POSITIVE_INFINITY, heading: Double? = null): Spot? {
        val k = cos(Math.toRadians(lat))
        val hx = heading?.let { sin(Math.toRadians(it)) } ?: 0.0
        val hy = heading?.let { cos(Math.toRadians(it)) } ?: 0.0
        var best = -1
        var bestD = Double.POSITIVE_INFINITY
        var bestT = 0.0
        for (i in 0 until at.size - 1) {
            if (at[i + 1] < from || at[i] > to) continue
            val ax = lons[i] * k
            val ay = lats[i]
            val dx = lons[i + 1] * k - ax
            val dy = lats[i + 1] - ay
            if (heading != null && dx * hx + dy * hy < 0) continue
            val len = dx * dx + dy * dy
            val t = if (len > 0) (((lon * k - ax) * dx + (lat - ay) * dy) / len).coerceIn(0.0, 1.0) else 0.0
            val ex = ax + dx * t - lon * k
            val ey = ay + dy * t - lat
            val d = ex * ex + ey * ey
            if (d < bestD) {
                bestD = d
                best = i
                bestT = t
            }
        }
        if (best < 0) return null
        val off = haversineMeters(lat, lon, lats[best] + (lats[best + 1] - lats[best]) * bestT, lons[best] + (lons[best + 1] - lons[best]) * bestT)
        return if (off <= MAX_OFF_ROUTE_M) Spot(at[best] + (at[best + 1] - at[best]) * bestT, off) else null
    }
}

fun pathOf(shape: List<ShapePoint>): RoutePath {
    val at = DoubleArray(shape.size)
    for (i in 1 until shape.size) at[i] = at[i - 1] + haversineMeters(shape[i - 1].lat, shape[i - 1].lon, shape[i].lat, shape[i].lon)
    return RoutePath(DoubleArray(shape.size) { shape[it].lat }, DoubleArray(shape.size) { shape[it].lon }, at)
}

class VehicleTrack(val path: RoutePath) {
    var s = 0.0
        private set
    var v = 0.0
        private set

    private class Fix(val s: Double, val ms: Long)

    private val fixes = ArrayDeque<Fix>()
    private var pace = 0.0
    private var gapMs = 30_000.0
    private var lateMs = 30_000.0
    private var lastMs = 0L

    /** False when [path] can't hold this bus. */
    fun fix(lat: Double, lon: Double, fixMs: Long, speedKmh: Double, heading: Double, nowMs: Long): Boolean {
        val last = fixes.lastOrNull()
        if (last != null && fixMs <= last.ms) return true
        val spot = (if (last != null) path.locate(lat, lon, last.s - MAX_OFF_ROUTE_M, last.s + MAX_MPS * (fixMs - last.ms) / 1000 + MAX_OFF_ROUTE_M)
        else path.locate(lat, lon, heading = heading)) ?: path.locate(lat, lon) ?: return false
        if (last != null && spot.s < last.s - BACKTRACK_M) return false
        if (last != null) {
            pace += (((spot.s - last.s) * 1000 / (fixMs - last.ms)).coerceIn(0.0, MAX_MPS) - pace) * TREND_WEIGHT
            gapMs = gapMs * 0.7 + (fixMs - last.ms) * 0.3
            lateMs = lateMs * 0.7 + (nowMs - fixMs) * 0.3
        } else {
            pace = (speedKmh / 3.6).coerceIn(0.0, MAX_MPS)
            lateMs = (nowMs - fixMs).toDouble()
        }
        fixes.addLast(Fix(spot.s, fixMs))
        if (fixes.size > 4) fixes.removeFirst()
        if (last == null) target(nowMs).let { (ts, tv) ->
            s = ts
            v = tv
        }
        return true
    }

    /** Position and speed at [nowMs]. */
    fun target(nowMs: Long): Pair<Double, Double> {
        val t = nowMs - lateMs * LATE_SHARE
        val last = fixes.last()
        if (t < last.ms) {
            val i = fixes.indexOfLast { it.ms <= t }
            if (i < 0) return fixes.first().s to 0.0
            val a = fixes[i]
            val b = fixes[i + 1]
            val speed = (b.s - a.s) * 1000 / (b.ms - a.ms)
            return a.s + speed * (t - a.ms) / 1000 to max(0.0, speed)
        }
        val age = (t - last.ms) / 1000
        val horizon = (lateMs + gapMs).coerceIn(10_000.0, 120_000.0) / 1000
        val coast = exp(-max(0.0, age - horizon) / COAST_S)
        val run = min(age, horizon) + COAST_S * (1 - coast)
        return min(last.s + pace * run, path.length) to pace * coast
    }

    /** True when the bus jumped. */
    fun step(nowMs: Long): Boolean {
        val dt = (nowMs - lastMs) / 1000.0
        lastMs = nowMs
        val (ts, tv) = target(nowMs)
        val err = ts - s
        if (abs(err) > JUMP_M) {
            s = ts
            v = tv
            return true
        }
        if (dt > 1 || dt < 0) {
            s = max(s, ts)
            v = tv
            return false
        }
        val want = (tv + err / (if (err > 0) CATCH_UP_S else HOLD_BACK_S)).coerceIn(0.0, MAX_MPS)
        v = max(0.0, v + (want - v).coerceIn(-BRAKE * dt, ACCEL * dt))
        s = min(s + v * dt, path.length)
        return false
    }

    val position: Pair<Double, Double> get() = path.pointAt(s)

    val heading: Double get() = path.headingAt(s)
}
