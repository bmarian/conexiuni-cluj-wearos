package online.bmarian.bus.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import online.bmarian.bus.Services
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.LiveState
import online.bmarian.bus.data.LiveStream
import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.StationLook
import online.bmarian.bus.data.Stop
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.DEPARTURES_SHOWN
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.nearestStation
import online.bmarian.bus.logic.stationDepartures
import online.bmarian.bus.ui.keepTime
import java.time.Instant
import java.time.LocalDateTime

// Stop offsets are hourly; Cluj's UTC offset is whole hours.
private const val HOUR_MS = 3_600_000L

class HomeStop(val stopId: Int, val name: String, val look: StationLook?, val meters: Double?, val departures: List<StopDeparture>)

class HomeUi(val nearby: HomeStop?, val saved: List<HomeStop>, val now: LocalDateTime)

private class Station(val stop: Stop, val meters: Double?)

private class Stations(val index: NetworkIndex, val nearby: Station?, val saved: List<Station>) {
    val all get() = listOfNotNull(nearby) + saved
    val stopIds get() = all.flatMap { station -> index.platforms(station.stop.stopId).map { it.stopId } }
}

private class Loaded(val stations: Stations, val infos: Map<Int, List<StopInfo>>)

class HomeViewModel : ViewModel() {
    private val here = MutableStateFlow<Fix?>(null)
    private val live = MutableStateFlow<LiveState?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val beat: StateFlow<Long?> = live.map { it?.asOfMs }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val stations: StateFlow<Stations?> = combine(Services.repository.network.index, here, Services.favorites.stops) { index, fix, saved ->
        if (index == null) return@combine null
        val nearby = fix?.let { nearestStation(index, it.lat, it.lon) }?.let { (stop, meters) -> Station(stop, meters) }
        val seen = hashSetOf<Int>()
        nearby?.let { seen += it.stop.station }
        val savedStations = saved.mapNotNull { id -> index.platforms(id).firstOrNull() }
            .filter { seen.add(it.station) }
            .map { Station(it, null) }
        Stations(index, nearby, savedStations)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val loaded = combine(stations, clock.map { it / HOUR_MS }.distinctUntilChanged()) { stations, _ ->
        if (stations == null) return@combine null
        val nowMs = System.currentTimeMillis()
        Loaded(stations, stations.all.associate { station ->
            station.stop.stopId to stations.index.stationInfos(station.stop.stopId, nowMs)
        })
    }.flowOn(Dispatchers.Default)

    val ui: StateFlow<HomeUi?> = combine(loaded, live, clock) { loaded, live, nowMs ->
        if (loaded == null) return@combine null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
        fun show(station: Station) = HomeStop(
            station.stop.stopId,
            station.stop.name,
            loaded.stations.index.look(station.stop.stopId),
            station.meters,
            stationDepartures(loaded.infos[station.stop.stopId].orEmpty(), live, now, nowMs).take(DEPARTURES_SHOWN),
        )
        HomeUi(loaded.stations.nearby?.let(::show), loaded.stations.saved.map(::show), now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun locate(fix: Fix?) {
        here.value = fix
    }

    suspend fun live() = coroutineScope {
        launch { clock.keepTime() }
        stations.map { it?.stopIds.orEmpty() }.distinctUntilChanged().collectLatest { ids ->
            if (ids.isNotEmpty()) LiveStream.live(ids, emptyList()).collect { live.value = it }
        }
    }
}
