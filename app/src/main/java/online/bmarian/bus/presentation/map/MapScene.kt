package online.bmarian.bus.presentation.map

import androidx.compose.ui.graphics.Color
import online.bmarian.bus.logic.bearingDegrees
import online.bmarian.bus.logic.haversineMeters
import kotlin.math.min

// Port of the phone's ui/map/MapScene.kt.

class MapBadge(val lat: Double, val lon: Double, val routes: List<Pair<String, Color>>)
class MapEnd(val lat: Double, val lon: Double, val color: Color)
class MapArrow(val lat: Double, val lon: Double, val bearing: Double)

private fun drawn(lines: List<MapLine>) = lines.filter { !it.dotted && it.points.size >= 2 }

fun lineStarts(lines: List<MapLine>): List<MapBadge> {
    val grouped = LinkedHashMap<String, Pair<Pair<Double, Double>, MutableList<Pair<String, Color>>>>()
    for (line in drawn(lines)) {
        if (line.name.isEmpty()) continue
        val start = line.points.first()
        val entry = grouped.getOrPut("%.4f,%.4f".format(start.first, start.second)) { start to mutableListOf() }
        if (entry.second.none { it.first == line.name }) entry.second += line.name to line.color
    }
    return grouped.values.map { (at, routes) -> MapBadge(at.first, at.second, routes) }
}

fun lineEnds(lines: List<MapLine>): List<MapEnd> = drawn(lines).map { MapEnd(it.points.last().first, it.points.last().second, it.color) }

fun startArrows(lines: List<MapLine>): List<MapArrow> = drawn(lines).mapNotNull { line ->
    routeHeading(line.points)?.let { MapArrow(line.points.first().first, line.points.first().second, it) }
}

private const val LOOP_SHARE = 0.25

fun routeHeading(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val (lat, lon) = points[0]
    fun fromStart(p: Pair<Double, Double>) = haversineMeters(lat, lon, p.first, p.second)
    val end = points.last()
    val toward = if (fromStart(end) >= points.maxOf(::fromStart) * LOOP_SHARE) end
    else points.subList(1, min(points.size, 20)).firstOrNull { fromStart(it) >= 30 } ?: points[1]
    return bearingDegrees(lat, lon, toward.first, toward.second)
}
