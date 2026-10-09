package online.bmarian.bus.data

import online.bmarian.bus.Services
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.computeStopDepartures
import online.bmarian.bus.logic.trackableTripIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime

class StopSnapshot(val stopId: Int, val name: String, val departures: List<StopDeparture>, val takenAtMs: Long)

suspend fun stopSnapshot(stopId: Int): StopSnapshot {
    val repository = Services.repository
    val info = repository.stopInfo(stopId)
    val tripIds = trackableTripIds(info)
    val shapes = runCatching { repository.shapes(tripIds) }.getOrDefault(emptyMap())
    val vehicles = if (tripIds.isEmpty()) emptyList() else runCatching { Api.vehicles(tripIds) }.getOrDefault(emptyList())
    val nowMs = System.currentTimeMillis()
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
    val departures = withContext(Dispatchers.Default) {
        computeStopDepartures(info, shapes, vehicles.groupBy { it.tripId.orEmpty() }, now, nowMs)
    }
    val favorites = Services.favorites
    val sorted = departures.sortedWith(
        compareBy({ if (favorites.isRouteFavorite(it.routeId)) 0 else 1 }, { it.minutesLeft }),
    )
    return StopSnapshot(stopId, info.name, sorted, nowMs)
}
