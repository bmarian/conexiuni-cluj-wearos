package online.bmarian.bus.presentation.stop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import online.bmarian.bus.Services
import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.VehicleStream
import online.bmarian.bus.data.directionFromTripId
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.buildShapeIndex
import online.bmarian.bus.logic.computeStopDepartures
import online.bmarian.bus.logic.indexVehicles
import online.bmarian.bus.logic.trackableTripIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime

const val TICK_MS = 10_000L

class StopUi(val departures: List<StopDeparture>, val now: LocalDateTime)

class StopVehicle(val id: String, val lat: Double, val lon: Double, val name: String, val color: String, val heading: Double)
class StopMapLine(val points: List<Pair<Double, Double>>, val color: String)
class StopMapLayer(val lines: List<StopMapLine>, val vehicles: List<StopVehicle>)

class StopViewModel(val stopId: Int) : ViewModel() {
    private val repository = Services.repository

    val name = MutableStateFlow(repository.stops.value.find { it.stopId == stopId }?.name)
    val location = MutableStateFlow(repository.stops.value.find { it.stopId == stopId }?.let { it.lat to it.lon })
    val failed = MutableStateFlow(false)

    private val info = MutableStateFlow<StopInfo?>(null)
    private val shapes = MutableStateFlow<Map<String, List<ShapePoint>>?>(null)
    private val vehicles = MutableStateFlow<Map<String, List<Vehicle>>>(emptyMap())
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val ui: StateFlow<StopUi?> = combine(info, shapes, vehicles, clock) { info, shapes, vehicles, nowMs ->
        if (info == null || shapes == null) return@combine null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
        StopUi(computeStopDepartures(info, shapes, vehicles, now, nowMs), now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Like the website: only favorite lines, their buses and the routes they run.
    val mapLayer: StateFlow<StopMapLayer> = combine(ui, shapes, vehicles, Services.favorites.routes) { ui, shapes, vehicles, _ ->
        if (ui == null) return@combine StopMapLayer(emptyList(), emptyList())
        val nowMs = System.currentTimeMillis()
        val lines = mutableListOf<StopMapLine>()
        val buses = mutableListOf<StopVehicle>()
        for (departure in ui.departures.filter(::isDepartureFavorite)) {
            val shape = shapes?.get(departure.tripId).orEmpty()
            if (shape.isEmpty()) continue
            val tracked = indexVehicles(departure.shortName, departure.color, buildShapeIndex(shape), nowMs, vehicles[departure.tripId].orEmpty())
            if (tracked.isEmpty()) continue
            lines += StopMapLine(shape.map { it.lat to it.lon }, departure.color)
            tracked.mapTo(buses) {
                StopVehicle("${it.vehicle.id}", it.vehicle.latitude, it.vehicle.longitude, departure.shortName, departure.color, it.heading)
            }
        }
        StopMapLayer(lines, buses)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, StopMapLayer(emptyList(), emptyList()))

    init {
        load()
    }

    fun load() {
        failed.value = false
        viewModelScope.launch {
            // The last copy first, so the timetable shows at once; then the fresh one.
            if (info.value == null) repository.cachedStopInfo(stopId)?.let { show(it) }
            try {
                show(repository.stopInfo(stopId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("Conexiuni", "stop load failed", e)
                if (info.value == null) failed.value = true
            }
        }
    }

    private suspend fun show(loaded: StopInfo) {
        name.value = loaded.name
        location.value = loaded.lat to loaded.lon
        val loadedShapes = runCatching { repository.shapes(trackableTripIds(loaded)) }.getOrDefault(shapes.value ?: emptyMap())
        info.value = loaded
        shapes.value = loadedShapes
    }

    /** Runs while the screen is visible: the clock ticks and live positions stream in. */
    suspend fun live() = coroutineScope {
        launch {
            while (true) {
                clock.value = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }
        info.filterNotNull().map { trackableTripIds(it) }.distinctUntilChanged().collectLatest { ids ->
            VehicleStream.vehicles(ids).collect { vehicles.value = it }
        }
    }

    fun isDepartureFavorite(departure: StopDeparture) =
        Services.favorites.isRouteFavorite(departure.routeId, directionFromTripId(departure.tripId))
}
