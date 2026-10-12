package online.bmarian.bus.data

import online.bmarian.bus.Services
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.stopDepartures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime

class StopSnapshot(val stopId: Int, val name: String, val departures: List<StopDeparture>, val takenAtMs: Long)

suspend fun stopSnapshot(stopId: Int): StopSnapshot {
    val repository = Services.repository
    val info = repository.stopInfo(stopId)
    val live = LiveStream.once(listOf(stopId), emptyList())
    val nowMs = System.currentTimeMillis()
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
    val departures = withContext(Dispatchers.Default) { stopDepartures(info, live, now, nowMs) }
    val favorites = Services.favorites
    val sorted = departures.sortedWith(
        compareBy({ if (favorites.isRouteFavorite(it.routeId)) 0 else 1 }, { it.minutesLeft }),
    )
    return StopSnapshot(stopId, info.name, sorted, nowMs)
}
