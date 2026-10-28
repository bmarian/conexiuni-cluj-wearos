package online.bmarian.bus.logic

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** The line from the spot nearest [lat]/[lon] on, when that spot is within [withinM]; the whole line otherwise. */
fun cutBehind(points: List<Pair<Double, Double>>, lat: Double, lon: Double, withinM: Double): List<Pair<Double, Double>> {
    if (points.size < 2) return points
    val kx = 111_320 * cos(Math.toRadians(lat))
    val ky = 110_540.0
    var bestOff = Double.POSITIVE_INFINITY
    var bestI = 1
    var bestT = 0.0
    for (i in 1 until points.size) {
        val (aLat, aLon) = points[i - 1]
        val (bLat, bLon) = points[i]
        val ax = (aLon - lon) * kx
        val ay = (aLat - lat) * ky
        val dx = (bLon - aLon) * kx
        val dy = (bLat - aLat) * ky
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
        val off = hypot(ax + t * dx, ay + t * dy)
        if (off < bestOff) {
            bestOff = off
            bestI = i
            bestT = t
        }
    }
    if (bestOff > withinM) return points
    val (aLat, aLon) = points[bestI - 1]
    val (bLat, bLon) = points[bestI]
    return listOf((aLat + bestT * (bLat - aLat)) to (aLon + bestT * (bLon - aLon))) + points.subList(bestI, points.size)
}

fun formatMeters(meters: Double): String =
    if (meters < 1000) "${Math.round(meters)} m" else String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000)
