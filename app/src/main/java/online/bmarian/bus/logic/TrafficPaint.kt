package online.bmarian.bus.logic

import androidx.compose.ui.graphics.Color
import online.bmarian.bus.data.TrafficStretch
import online.bmarian.bus.data.decodePolyline

val TRAFFIC_COLORS = listOf(Color(0xFF34A853), Color(0xFFFBBC04), Color(0xFFEA4335), Color(0xFFA50E0E))

class TrafficRun(val from: Float, val to: Float, val color: Color)

/** Where along [points] each stretch runs, as shares of the length, in its band's colour. */
fun trafficRuns(stretches: List<TrafficStretch>, points: List<Pair<Double, Double>>): List<TrafficRun> {
    val path = pathThrough(DoubleArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second })
    if (points.size < 2 || path.length <= 0) return emptyList()
    var end = 0.0
    return stretches.mapNotNull { stretch ->
        val color = TRAFFIC_COLORS.getOrNull(stretch.band) ?: return@mapNotNull null
        val shape = decodePolyline(stretch.path)
        if (shape.size < 2) return@mapNotNull null
        val from = path.locate(shape.first().first, shape.first().second, from = end)?.s ?: return@mapNotNull null
        val to = path.locate(shape.last().first, shape.last().second, from = from)?.s ?: return@mapNotNull null
        if (to <= from) return@mapNotNull null
        end = to
        TrafficRun((from / path.length).toFloat(), (to / path.length).toFloat(), color)
    }
}

/** [points] up to the share [done] of its length, as parts in the runs' colours and [color] between them. */
fun paintedParts(points: List<Pair<Double, Double>>, color: Color, runs: List<TrafficRun>, done: Float = 1f): List<Pair<List<Pair<Double, Double>>, Color>> {
    val parts = mutableListOf<Pair<List<Pair<Double, Double>>, Color>>()
    var at = 0f
    for (run in runs + TrafficRun(1f, 1f, color)) {
        parts += partBetween(points, at, minOf(run.from, done)) to color
        parts += partBetween(points, run.from, minOf(run.to, done)) to run.color
        at = run.to
        if (at >= done) break
    }
    return parts.filter { it.first.size >= 2 }
}
