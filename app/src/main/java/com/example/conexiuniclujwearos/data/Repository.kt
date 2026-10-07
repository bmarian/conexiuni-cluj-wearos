package com.example.conexiuniclujwearos.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val LIST_REFRESH_MS = 30 * 60_000L
private const val SHAPE_INFO_TTL_MS = 10 * 60_000L
private const val SHAPE_TTL_MS = 3 * 24 * 60 * 60_000L

/**
 * Routes and stops start from the last session's copy so favorites show before the
 * network answers; everything else is fetched when a screen asks for it.
 */
class Repository(context: Context) {
    private val routesFile = File(context.filesDir, "routes.json")
    private val stopsFile = File(context.filesDir, "stops.json")
    private val shapeDir = File(context.filesDir, "shapes").apply { mkdirs() }

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops

    private val routesMutex = Mutex()
    private val stopsMutex = Mutex()
    private var routesFetchedAt = 0L
    private var stopsFetchedAt = 0L

    private val shapeInfoCache = HashMap<Int, Pair<Long, ShapeInfo>>()
    private val shapeCache = LruCache<String, List<ShapePoint>>(24)

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_routes.value.isEmpty()) readList<Route>(routesFile)?.let { _routes.value = it }
        if (_stops.value.isEmpty()) readList<Stop>(stopsFile)?.let { _stops.value = it }
    }

    private inline fun <reified T> readList(file: File): List<T>? =
        runCatching { Api.json.decodeFromString<List<T>>(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** False when the list could not be fetched; the cached copy stays in place. */
    suspend fun refreshRoutes(force: Boolean = false): Boolean = routesMutex.withLock {
        if (!force && System.currentTimeMillis() - routesFetchedAt < LIST_REFRESH_MS) return true
        runCatching {
            val text = Api.routesText()
            val list = withContext(Dispatchers.Default) { Api.json.decodeFromString<List<Route>>(text) }
            if (list.isNotEmpty()) {
                _routes.value = list
                routesFetchedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) { routesFile.writeText(text) }
            }
        }.isSuccess
    }

    suspend fun refreshStops(force: Boolean = false): Boolean = stopsMutex.withLock {
        if (!force && System.currentTimeMillis() - stopsFetchedAt < LIST_REFRESH_MS) return true
        runCatching {
            val text = Api.stopsText()
            val list = withContext(Dispatchers.Default) { Api.json.decodeFromString<List<Stop>>(text) }
            if (list.isNotEmpty()) {
                _stops.value = list
                stopsFetchedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) { stopsFile.writeText(text) }
            }
        }.isSuccess
    }

    suspend fun route(routeId: Int): Route? {
        loadCached()
        _routes.value.find { it.routeId == routeId }?.let { return it }
        refreshRoutes(force = true)
        return _routes.value.find { it.routeId == routeId }
    }

    suspend fun stopInfo(stopId: Int): StopInfo = Api.stopInfo(stopId)

    /** The last copy of a stop, to show while the fresh one loads. */
    suspend fun cachedStopInfo(stopId: Int): StopInfo? = Api.cached(Api.stopInfoPath(stopId))

    suspend fun cachedShapeInfo(route: Route): ShapeInfo? {
        synchronized(shapeInfoCache) { shapeInfoCache[route.routeId]?.let { return it.second } }
        val stopTimes = Api.cached<List<StopTime>>(Api.stopTimesPath(route.shortName)) ?: return null
        return assembleShapeInfo(route, Api.cached<Timetable>(Api.timetablePath(route.shortName)), stopTimes)
    }

    /** Timetable and stop times for one route. Port of useRouteShapeInfoApi.fetchShapeInfo. */
    suspend fun shapeInfo(route: Route): ShapeInfo {
        synchronized(shapeInfoCache) {
            shapeInfoCache[route.routeId]?.let { (at, info) ->
                if (System.currentTimeMillis() - at < SHAPE_INFO_TTL_MS) return info
            }
        }
        val info = coroutineScope {
            val timetable = async { runCatching { Api.timetable(route.shortName) }.getOrNull() }
            val stopTimes = async { runCatching { Api.stopTimes(route.shortName) }.getOrNull() }
            assembleShapeInfo(route, timetable.await(), stopTimes.await().orEmpty())
        }
        synchronized(shapeInfoCache) { shapeInfoCache[route.routeId] = System.currentTimeMillis() to info }
        return info
    }

    /**
     * Shape points per trip id, in order. Trips the server has no shape for map to an empty list.
     * Shapes almost never change, so they are kept on disk for days.
     */
    suspend fun shapes(tripIds: List<String>): Map<String, List<ShapePoint>> {
        val ids = tripIds.distinct().sorted()
        val result = HashMap<String, List<ShapePoint>>()
        val stale = HashMap<String, List<ShapePoint>>()
        val missing = withContext(Dispatchers.IO) {
            ids.filter { id ->
                shapeCache.get(id)?.let { result[id] = it; return@filter false }
                val file = shapeFile(id)
                val saved = if (file.exists()) readList<ShapePoint>(file) else null
                if (saved != null && System.currentTimeMillis() - file.lastModified() < SHAPE_TTL_MS) {
                    shapeCache.put(id, saved)
                    result[id] = saved
                    false
                } else {
                    if (saved != null) stale[id] = saved
                    true
                }
            }
        }
        if (missing.isEmpty()) return result
        val points = try {
            Api.shapes(missing)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || stale.isEmpty()) throw e
            result.putAll(stale)
            return result
        }
        withContext(Dispatchers.IO) {
            val grouped = points.groupBy { it.shapeId }
            for (id in missing) {
                val shape = grouped[id].orEmpty().sortedBy { it.sequence }
                if (shape.isNotEmpty()) {
                    shapeCache.put(id, shape)
                    runCatching { shapeFile(id).writeText(Api.json.encodeToString(shape)) }
                }
                result[id] = shape
            }
        }
        return result
    }

    private fun shapeFile(tripId: String) = File(shapeDir, "${tripId.filter { it.isLetterOrDigit() || it == '_' }}.json")
}

/** A route's timetable and stop times, with names the timetable lacks taken from the route. */
fun assembleShapeInfo(route: Route, timetable: Timetable?, stopTimes: List<StopTime>): ShapeInfo {
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
    )
}
