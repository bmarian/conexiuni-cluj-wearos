package online.bmarian.bus.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDateTime
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
import online.bmarian.bus.data.ShapePoint
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.boardingStop
import online.bmarian.bus.logic.readKey
import online.bmarian.bus.logic.stopDepartures
import online.bmarian.bus.logic.tripOf
import online.bmarian.bus.ui.keepTime

/** [departures]: each line's next bus at its boarding stop, null once none is left today. */
class GroupUi(val departures: List<StopDeparture?>, val now: LocalDateTime)

class GroupViewModel(key: String) : ViewModel() {
    private val index = Services.repository.network.index
    val lines = readKey(key)
    private val trips = lines.map(::tripOf)
    private val here = MutableStateFlow<Fix?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val _shapes = MutableStateFlow<Map<String, List<ShapePoint>>>(emptyMap())
    val shapes: StateFlow<Map<String, List<ShapePoint>>> = _shapes
    private val _live = MutableStateFlow<LiveState?>(null)
    val live: StateFlow<LiveState?> = _live
    val beat: StateFlow<Long?> = _live.map { it?.asOfMs }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val boards: StateFlow<List<Int?>> = combine(index, here) { index, fix ->
        if (index == null) emptyList() else lines.map { index.boardingStop(it, fix?.lat, fix?.lon) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val ui: StateFlow<GroupUi?> = combine(index, boards, _live, clock) { index, boards, live, nowMs ->
        if (index == null) return@combine null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
        val departures = lines.mapIndexed { i, line ->
            val info = boards.getOrNull(i)?.let { index.stopInfo(it, nowMs) } ?: return@mapIndexed null
            stopDepartures(info, live, now, nowMs, tripOf(line)).firstOrNull()
        }
        GroupUi(departures, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun locate(fix: Fix?) {
        here.value = fix
    }

    suspend fun load() {
        _shapes.value = runCatching { Services.repository.shapes(trips) }.getOrDefault(emptyMap())
    }

    suspend fun live() = coroutineScope {
        launch { clock.keepTime() }
        boards.map { it.filterNotNull().distinct() }.distinctUntilChanged().collectLatest { stops ->
            LiveStream.live(stops, trips).collect { _live.value = it }
        }
    }
}
