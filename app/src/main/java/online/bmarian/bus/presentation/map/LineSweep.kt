package online.bmarian.bus.presentation.map

import online.bmarian.bus.logic.RoutePath
import online.bmarian.bus.logic.haversineMeters

// Port of LineSweep.kt.

const val SWEEP_MS = 1130L

fun routePath(points: List<Pair<Double, Double>>): RoutePath {
    val at = DoubleArray(points.size)
    for (i in 1 until points.size) at[i] = at[i - 1] + haversineMeters(points[i - 1].first, points[i - 1].second, points[i].first, points[i].second)
    return RoutePath(DoubleArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second }, at)
}

fun partTo(points: List<Pair<Double, Double>>, to: Float): List<Pair<Double, Double>> {
    if (points.size < 2 || to <= 0f) return emptyList()
    val steps = points.zipWithNext { a, b -> haversineMeters(a.first, a.second, b.first, b.second) }
    val end = steps.sum() * to.coerceAtMost(1f)
    val out = mutableListOf(points[0])
    var walked = 0.0
    for ((i, step) in steps.withIndex()) {
        if (walked + step <= end) {
            out += points[i + 1]
            walked += step
            continue
        }
        val f = if (step > 0) (end - walked) / step else 0.0
        val a = points[i]
        val b = points[i + 1]
        out += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
        break
    }
    return out
}
