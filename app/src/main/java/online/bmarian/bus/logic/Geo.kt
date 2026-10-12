package online.bmarian.bus.logic

import kotlin.math.atan2
import kotlin.math.cos
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

fun findClosestShapeIdx(lat: Double, lon: Double, shape: List<online.bmarian.bus.data.ShapePoint>): Int {
    var idx = -1
    var best = Double.POSITIVE_INFINITY
    for (i in shape.indices) {
        val d = haversineMeters(shape[i].lat, shape[i].lon, lat, lon)
        if (d < best) {
            best = d
            idx = i
        }
    }
    return idx
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun formatMeters(meters: Double): String =
    if (meters < 1000) "${Math.round(meters)} m" else String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000)
