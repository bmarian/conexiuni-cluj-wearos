package online.bmarian.bus.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import online.bmarian.bus.logic.ScheduleDays
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val SHAPE_TTL_MS = 3 * 24 * 60 * 60_000L

/** The current city's lists for the screens; any city's data for what runs in the background. */
class Repository(private val context: Context) {
    private val shapeRoot = File(context.filesDir, "shapes")
    private val stores = HashMap<String, NetworkStore>()

    @Volatile var city: String = Cities.current.value
        private set

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops

    private val shapeCache = LruCache<String, List<ShapePoint>>(24)

    init {
        // Shapes saved before cities lay loose in the folder.
        shapeRoot.listFiles { file -> file.isFile }?.forEach { it.delete() }
    }

    fun store(city: String): NetworkStore = synchronized(stores) { stores.getOrPut(city) { NetworkStore(context, city) } }

    val network: NetworkStore get() = store(city)

    /** Shows [slug]'s lists; its saved bundle loads with the next [loadCached]. */
    fun use(slug: String) {
        if (slug == city) return
        city = slug
        _routes.value = emptyList()
        _stops.value = emptyList()
        store(slug).index.value?.let(::useLists)
    }

    suspend fun loadCached(city: String = this.city) {
        store(city).load()
        if (city == this.city) network.index.value?.let(::useLists)
    }

    suspend fun refreshNetwork(now: Boolean = false, city: String = this.city): Boolean {
        val ok = store(city).refresh(now)
        if (city == this.city) network.index.value?.let(::useLists)
        return ok
    }

    private fun useLists(index: NetworkIndex) {
        ScheduleDays.use(index.calendar)
        if (index.routes.isNotEmpty() && _routes.value != index.routes) _routes.value = index.routes
        if (index.stops.isNotEmpty() && _stops.value != index.stops) _stops.value = index.stops
    }

    private fun nowMs() = System.currentTimeMillis()

    /** [city]'s bundle, fetched when it has none yet. */
    private suspend fun index(city: String, refresh: Boolean): NetworkIndex? {
        loadCached(city)
        if (refresh) refreshNetwork(now = true, city = city)
        return store(city).index.value
    }

    suspend fun route(routeId: Int): Route? {
        loadCached()
        _routes.value.find { it.routeId == routeId }?.let { return it }
        refreshNetwork(now = true)
        return _routes.value.find { it.routeId == routeId }
    }

    fun cachedStopInfo(stopId: Int, city: String = this.city): StopInfo? = store(city).index.value?.stopInfo(stopId, nowMs())

    suspend fun stopInfo(stopId: Int, city: String = this.city): StopInfo {
        index(city, refresh = false)?.stopInfo(stopId, nowMs())?.let { return it }
        return index(city, refresh = true)?.stopInfo(stopId, nowMs()) ?: throw IOException("no network data for stop $stopId")
    }

    fun cachedShapeInfo(route: Route): ShapeInfo? = network.index.value?.shapeInfo(route, nowMs())

    suspend fun shapeInfo(route: Route): ShapeInfo {
        cachedShapeInfo(route)?.let { return it }
        refreshNetwork(now = true)
        return cachedShapeInfo(route) ?: throw IOException("no network data for route ${route.shortName}")
    }

    private class SavedShape(val hash: String, val points: List<ShapePoint>, val savedAt: Long)

    suspend fun shapes(tripIds: List<String>, city: String = this.city): Map<String, List<ShapePoint>> {
        val ids = tripIds.distinct().sorted()
        val index = store(city).index.value
        val dir = File(shapeRoot, city)
        val result = HashMap<String, List<ShapePoint>>()
        val stale = HashMap<String, List<ShapePoint>>()
        val missing = withContext(Dispatchers.IO) {
            ids.filter { id ->
                val wanted = index?.shapeHash(id)
                shapeCache.get("$city/$id@$wanted")?.let { result[id] = it; return@filter false }
                val saved = readShape(dir, id)
                val good = saved != null &&
                    if (wanted != null) saved.hash == wanted else nowMs() - saved.savedAt < SHAPE_TTL_MS
                if (good) {
                    shapeCache.put("$city/$id@$wanted", saved!!.points)
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
            Api.shapePolylines(missing, city)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || stale.isEmpty()) throw e
            result.putAll(stale)
            return result
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            for (id in missing) {
                val polyline = encoded[id].orEmpty()
                val shape = decodeShape(id, polyline)
                if (shape.isNotEmpty()) {
                    shapeCache.put("$city/$id@${index?.shapeHash(id)}", shape)
                    runCatching { shapeFile(dir, id).writeText(shapeHash(polyline) + "\n" + polyline) }
                }
                result[id] = shape
            }
        }
        return result
    }

    private fun readShape(dir: File, tripId: String): SavedShape? {
        val file = shapeFile(dir, tripId)
        if (!file.exists()) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val points = decodeShape(tripId, lines.getOrNull(1).orEmpty()).takeIf { it.isNotEmpty() } ?: return null
        return SavedShape(lines[0], points, file.lastModified())
    }

    private fun decodeShape(tripId: String, polyline: String): List<ShapePoint> =
        decodePolyline(polyline, precision = 6).mapIndexed { i, (lat, lon) -> ShapePoint(tripId, lat, lon, i) }

    private fun shapeFile(dir: File, tripId: String) = File(dir, "${tripId.filter { it.isLetterOrDigit() || it == '_' }}.poly")
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
