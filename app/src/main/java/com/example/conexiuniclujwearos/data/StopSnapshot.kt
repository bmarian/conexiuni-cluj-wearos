package com.example.conexiuniclujwearos.data

import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.logic.CLUJ_ZONE
import com.example.conexiuniclujwearos.logic.StopDeparture
import com.example.conexiuniclujwearos.logic.computeStopDepartures
import com.example.conexiuniclujwearos.logic.trackableTripIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime

class StopSnapshot(val stopId: Int, val name: String, val departures: List<StopDeparture>, val takenAtMs: Long)

/** One look at a stop, for the tile and the complication: the stream is too much for them. */
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
        compareBy({ if (favorites.isRouteFavorite(it.routeId, directionFromTripId(it.tripId))) 0 else 1 }, { it.minutesLeft }),
    )
    return StopSnapshot(stopId, info.name, sorted, nowMs)
}
