package online.bmarian.bus.presentation.stop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import online.bmarian.bus.Services
import online.bmarian.bus.presentation.keepTime
import online.bmarian.bus.data.LiveState
import online.bmarian.bus.data.LiveStream
import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.routeIdFromTripId
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.stationDepartures
import online.bmarian.bus.logic.trackableTripIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime

class StopUi(val departures: List<StopDeparture>, val now: LocalDateTime)

class StopVehicle(val id: String, val lat: Double, val lon: Double, val name: String, val color: String, val heading: Double, val vehicle: Vehicle)
class StopMapLine(val points: List<Pair<Double, Double>>, val name: String, val color: String)
class StopMapLayer(val lines: List<StopMapLine>, val vehicles: List<StopVehicle>)

class StopViewModel(val stopId: Int) : ViewModel() {
    private val repository = Services.repository

    val name = MutableStateFlow(repository.stops.value.find { it.stopId == stopId }?.name)
    val location = MutableStateFlow(repository.stops.value.find { it.stopId == stopId }?.let { it.lat to it.lon })
    val failed = MutableStateFlow(false)

    private val info = MutableStateFlow<StopInfo?>(null)
    private val shapes = MutableStateFlow<Map<String, List<ShapePoint>>>(emptyMap())
    private val live = MutableStateFlow<LiveState?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val beat: StateFlow<Long?> = live.map { it?.asOfMs }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val infos: StateFlow<List<StopInfo>> = combine(info, repository.network.index) { info, index ->
        if (info == null) return@combine emptyList()
        val nowMs = System.currentTimeMillis()
        listOf(info) + index?.stationInfos(stopId, nowMs)?.drop(1).orEmpty()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val ui: StateFlow<StopUi?> = combine(infos, live, clock) { infos, live, nowMs ->
        if (infos.isEmpty()) return@combine null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
        StopUi(stationDepartures(infos, live, now, nowMs), now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mapLayer: StateFlow<StopMapLayer> = combine(ui, shapes, live, Services.favorites.routes) { ui, shapes, live, _ ->
        if (ui == null || live == null) return@combine StopMapLayer(emptyList(), emptyList())
        val lines = mutableListOf<StopMapLine>()
        val buses = mutableListOf<StopVehicle>()
        for (departure in ui.departures.filter(::isDepartureFavorite)) {
            val shape = shapes[departure.tripId].orEmpty()
            val vehicles = live.vehicles(departure.tripId)
            if (shape.isEmpty() || vehicles.isEmpty()) continue
            lines += StopMapLine(shape.map { it.lat to it.lon }, departure.shortName, departure.color)
            vehicles.mapTo(buses) { StopVehicle("${it.id}", it.latitude, it.longitude, departure.shortName, departure.color, it.heading, it) }
        }
        StopMapLayer(lines, buses)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, StopMapLayer(emptyList(), emptyList()))

    // Buses are drawn for favourite lines only, so only their trips are subscribed.
    private val favoriteTripIds = combine(infos, Services.favorites.routes) { infos, _ ->
        infos.flatMap(::trackableTripIds).distinct().filter { tripId -> routeIdFromTripId(tripId)?.let(Services.favorites::isRouteFavorite) == true }
    }.distinctUntilChanged()

    init {
        load()
    }

    fun load() {
        failed.value = false
        viewModelScope.launch {
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

    private fun show(loaded: StopInfo) {
        name.value = loaded.name
        location.value = loaded.lat to loaded.lon
        info.value = loaded
    }

    suspend fun live() = coroutineScope {
        launch { clock.keepTime() }
        launch {
            favoriteTripIds.collectLatest { ids ->
                shapes.value = runCatching { repository.shapes(ids) }.getOrDefault(emptyMap())
            }
        }
        combine(infos.map { list -> list.map { it.stopId }.ifEmpty { listOf(stopId) } }.distinctUntilChanged(), favoriteTripIds) { a, b -> a to b }
            .collectLatest { (stopIds, ids) -> LiveStream.live(stopIds, ids).collect { live.value = it } }
    }

    fun isDepartureFavorite(departure: StopDeparture) =
        Services.favorites.isRouteFavorite(departure.routeId)

    fun platformOf(departure: StopDeparture) = if (departure.stopId != 0) departure.stopId else stopId

    fun platform(stopId: Int): StopInfo? = infos.value.firstOrNull { it.stopId == stopId }
}
