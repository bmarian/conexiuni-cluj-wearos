package online.bmarian.bus.presentation.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import online.bmarian.bus.Services
import online.bmarian.bus.presentation.AmbientTicks
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.LiveState
import online.bmarian.bus.data.LiveStream
import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.tripIdFor
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.DirectionShape
import online.bmarian.bus.logic.IndexedStop
import online.bmarian.bus.logic.MarkedArrival
import online.bmarian.bus.logic.directionShape
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.logic.routeStopArrivals
import online.bmarian.bus.logic.stopsForDirection
import online.bmarian.bus.presentation.stop.TICK_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime

class RouteDirectionUi(
    val stops: List<IndexedStop>,
    val arrivals: List<List<MarkedArrival>>,
    val shape: DirectionShape?,
    val vehicles: List<Vehicle>,
    val roundTripVehicles: List<Vehicle>,
    val now: LocalDateTime,
)

class RouteViewModel(val routeId: Int, initialDirection: String, val fromStopId: Int?) : ViewModel() {
    private val repository = Services.repository

    val direction = MutableStateFlow(if (initialDirection == "1") "1" else "0")
    private var wantsAuto = initialDirection == "auto"
    private var here: Fix? = null
    val shapeInfo = MutableStateFlow<ShapeInfo?>(null)
    val failed = MutableStateFlow(false)

    private val shapes = MutableStateFlow<Map<String, DirectionShape>>(emptyMap())
    private val live = MutableStateFlow<LiveState?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val ui: StateFlow<RouteDirectionUi?> = combine(
        shapeInfo, direction, shapes, live, clock,
    ) { info: ShapeInfo?, dir: String, shapes: Map<String, DirectionShape>, live: LiveState?, nowMs: Long ->
        if (info == null) return@combine null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
        val tripId = tripIdFor(routeId, dir)
        val stops = stopsForDirection(info, tripId)
        RouteDirectionUi(
            stops = stops,
            arrivals = routeStopArrivals(info, stops, dir == "0", tripId, live, now, nowMs),
            shape = shapes[dir],
            vehicles = live?.vehicles(tripId).orEmpty(),
            roundTripVehicles = live?.roundTrip(tripId, tripIdFor(routeId, if (dir == "0") "1" else "0")).orEmpty(),
            now = now,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
    }

    fun load() {
        failed.value = false
        viewModelScope.launch {
            try {
                val route = repository.route(routeId) ?: throw IllegalStateException("unknown route")
                if (shapeInfo.value == null) repository.cachedShapeInfo(route)?.let { show(it) }
                show(repository.shapeInfo(route))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("Conexiuni", "route load failed", e)
                if (shapeInfo.value == null) failed.value = true
            }
        }
    }

    private suspend fun show(info: ShapeInfo) {
        val ids = listOf("0", "1").map { it to tripIdFor(routeId, it) }
        val loaded = runCatching { repository.shapes(ids.map { it.second }) }.getOrDefault(emptyMap())
        shapeInfo.value = info
        if (wantsAuto) pickNearest(info)
        if (loaded.isNotEmpty() || shapes.value.isEmpty()) {
            shapes.value = ids.mapNotNull { (dir, tripId) -> directionShape(loaded[tripId].orEmpty())?.let { dir to it } }.toMap()
        }
    }

    /** For "auto": the direction with the stop nearest to [fix], once both are known. */
    fun locate(fix: Fix) {
        if (!wantsAuto) return
        here = fix
        shapeInfo.value?.let(::pickNearest)
    }

    private fun pickNearest(info: ShapeInfo) {
        val fix = here ?: return
        val byTrip = mapOf(tripIdFor(routeId, "0") to "0", tripIdFor(routeId, "1") to "1")
        val nearest = info.stopTimes
            .filter { it.tripId in byTrip && it.lat != 0.0 }
            .minByOrNull { haversineMeters(fix.lat, fix.lon, it.lat, it.lon) } ?: return
        wantsAuto = false
        direction.value = byTrip.getValue(nearest.tripId)
    }

    fun hasDirection(dir: String): Boolean =
        shapeInfo.value?.stopTimes?.any { it.tripId == tripIdFor(routeId, dir) } == true

    fun swap() {
        wantsAuto = false
        direction.value = if (direction.value == "0") "1" else "0"
    }

    suspend fun live() = coroutineScope {
        launch { AmbientTicks.ticks.collect { clock.value = System.currentTimeMillis() } }
        launch {
            while (true) {
                clock.value = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }
        LiveStream.live(emptyList(), listOf(tripIdFor(routeId, "0"), tripIdFor(routeId, "1"))).collect { live.value = it }
    }
}
