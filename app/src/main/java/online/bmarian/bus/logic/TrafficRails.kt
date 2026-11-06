package online.bmarian.bus.logic

import androidx.compose.ui.graphics.Color
import online.bmarian.bus.data.TrafficStretch
import online.bmarian.bus.data.decodePolyline

class Rail(val band: Int, val color: Color, val width: Float)

// Port of trafficRails.ts.
val RAILS = listOf(Rail(2, Color(0xFFEA4335), 2f), Rail(3, Color(0xFFA50E0E), 3f))
const val RAIL_GAP = 1.5f

class TrafficRun(val from: Float, val to: Float, val band: Int)

/** Where along [points] each railed stretch runs, as shares of the length. */
fun trafficRuns(stretches: List<TrafficStretch>, points: List<Pair<Double, Double>>): List<TrafficRun> {
    val railed = stretches.filter { stretch -> RAILS.any { it.band == stretch.band } }
    if (railed.isEmpty() || points.size < 2) return emptyList()
    val path = pathThrough(DoubleArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second })
    if (path.length <= 0) return emptyList()
    var end = 0.0
    return railed.mapNotNull { stretch ->
        val shape = decodePolyline(stretch.path)
        if (shape.size < 2) return@mapNotNull null
        val from = path.locate(shape.first().first, shape.first().second, from = end)?.s ?: return@mapNotNull null
        val to = path.locate(shape.last().first, shape.last().second, from = from)?.s ?: return@mapNotNull null
        if (to <= from) return@mapNotNull null
        end = to
        TrafficRun((from / path.length).toFloat(), (to / path.length).toFloat(), stretch.band)
    }
}

fun railParts(points: List<Pair<Double, Double>>, runs: List<TrafficRun>, done: Float = 1f): List<Pair<List<Pair<Double, Double>>, Int>> =
    runs.filter { it.from < done }.map { partBetween(points, it.from, minOf(it.to, done)) to it.band }.filter { it.first.size >= 2 }
