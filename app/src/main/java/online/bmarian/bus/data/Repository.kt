package online.bmarian.bus.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val SHAPE_TTL_MS = 3 * 24 * 60 * 60_000L

class Repository(context: Context) {
    private val shapeDir = File(context.filesDir, "shapes").apply { mkdirs() }

    val network = NetworkStore(context)

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops

    private val shapeCache = LruCache<String, List<ShapePoint>>(24)

    suspend fun loadCached() {
        network.load()
        network.index.value?.let(::useLists)
    }

    suspend fun refreshNetwork(now: Boolean = false): Boolean {
        val ok = network.refresh(now)
        network.index.value?.let(::useLists)
        return ok
    }

    private fun useLists(index: NetworkIndex) {
        if (index.routes.isNotEmpty() && _routes.value != index.routes) _routes.value = index.routes
        if (index.stops.isNotEmpty() && _stops.value != index.stops) _stops.value = index.stops
    }

    private fun nowMs() = System.currentTimeMillis()

    suspend fun route(routeId: Int): Route? {
        loadCached()
        _routes.value.find { it.routeId == routeId }?.let { return it }
        refreshNetwork(now = true)
        return _routes.value.find { it.routeId == routeId }
    }

    fun cachedStopInfo(stopId: Int): StopInfo? = network.index.value?.stopInfo(stopId, nowMs())

    suspend fun stopInfo(stopId: Int): StopInfo {
        cachedStopInfo(stopId)?.let { return it }
        refreshNetwork(now = true)
        return cachedStopInfo(stopId) ?: throw IOException("no network data for stop $stopId")
    }

    fun cachedShapeInfo(route: Route): ShapeInfo? = network.index.value?.shapeInfo(route, nowMs())

    suspend fun shapeInfo(route: Route): ShapeInfo {
        cachedShapeInfo(route)?.let { return it }
        refreshNetwork(now = true)
        return cachedShapeInfo(route) ?: throw IOException("no network data for route ${route.shortName}")
    }

    private class SavedShape(val hash: String, val points: List<ShapePoint>, val savedAt: Long)

    suspend fun shapes(tripIds: List<String>): Map<String, List<ShapePoint>> {
        val ids = tripIds.distinct().sorted()
        val index = network.index.value
        val result = HashMap<String, List<ShapePoint>>()
        val stale = HashMap<String, List<ShapePoint>>()
        val missing = withContext(Dispatchers.IO) {
            ids.filter { id ->
                val wanted = index?.shapeHash(id)
                shapeCache.get("$id@$wanted")?.let { result[id] = it; return@filter false }
                val saved = readShape(id)
                val good = saved != null &&
                    if (wanted != null) saved.hash == wanted else nowMs() - saved.savedAt < SHAPE_TTL_MS
                if (good) {
                    shapeCache.put("$id@$wanted", saved!!.points)
                    result[id] = saved.points
                    false
                } else {
                    if (saved != null) stale[id] = saved.points
                    true
                }
            }
        }
        if (missing.isEmpty()) return result
        val encoded = try {
            Api.shapePolylines(missing)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || stale.isEmpty()) throw e
            result.putAll(stale)
            return result
        }
        withContext(Dispatchers.IO) {
            for (id in missing) {
                val polyline = encoded[id].orEmpty()
                val shape = decodeShape(id, polyline)
                if (shape.isNotEmpty()) {
                    shapeCache.put("$id@${index?.shapeHash(id)}", shape)
                    runCatching { shapeFile(id).writeText(shapeHash(polyline) + "\n" + polyline) }
                }
                result[id] = shape
            }
        }
        return result
    }

    private fun readShape(tripId: String): SavedShape? {
        val file = shapeFile(tripId)
        if (!file.exists()) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val points = decodeShape(tripId, lines.getOrNull(1).orEmpty()).takeIf { it.isNotEmpty() } ?: return null
        return SavedShape(lines[0], points, file.lastModified())
    }

    private fun decodeShape(tripId: String, polyline: String): List<ShapePoint> =
        decodePolyline(polyline, precision = 6).mapIndexed { i, (lat, lon) -> ShapePoint(tripId, lat, lon, i) }

    private fun shapeFile(tripId: String) = File(shapeDir, "${tripId.filter { it.isLetterOrDigit() || it == '_' }}.poly")
}

fun shapeHash(polyline: String): String =
    MessageDigest.getInstance("SHA-256").digest(polyline.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

fun assembleShapeInfo(route: Route, timetable: Timetable?, stopTimes: List<StopTime>, lateGraceMin: Double = 1.0): ShapeInfo {
    val outbound = stopTimes.filter { it.tripId.endsWith(OUTGOING_SUFFIX) }.sortedBy { it.stopSequence }
    var tt = timetable ?: Timetable(routeLongName = route.longName)
    if (tt.routeLongName.isEmpty()) tt = tt.copy(routeLongName = route.longName)
    if (tt.inStopName.isEmpty()) tt = tt.copy(inStopName = outbound.firstOrNull()?.headsign?.trim().orEmpty())
    if (tt.outStopName.isEmpty()) tt = tt.copy(outStopName = outbound.lastOrNull()?.headsign?.trim().orEmpty())
    return ShapeInfo(
        shortName = route.shortName,
        longName = route.longName,
        routeId = route.routeId,
        type = route.type,
        color = route.color,
        stopTimesPlural = stopTimes,
        timetable = tt,
        lateGraceMin = lateGraceMin,
    )
}
