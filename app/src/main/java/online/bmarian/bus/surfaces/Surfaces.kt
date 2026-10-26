package online.bmarian.bus.surfaces

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.stopDepartures
import online.bmarian.bus.logic.stationDepartures
import online.bmarian.bus.logic.nearestStation
import online.bmarian.bus.data.LiveStream
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.logic.formatMinutesFromNow
import java.time.Instant
import java.time.LocalDateTime

private const val FETCH_TIMEOUT_MS = 5_000L

class NextAtStop(val stopId: Int, val name: String, val departure: StopDeparture?, val minutes: Int?, val nowMs: Long)

suspend fun nextAtFavoriteStops(count: Int): List<NextAtStop> = coroutineScope {
    val repository = Services.repository
    repository.loadCached()
    val names = repository.stops.value.associate { it.stopId to it.name }
    Services.favorites.stops.value.take(count).map { id ->
        async {
            val info = repository.cachedStopInfo(id)
                ?: withTimeoutOrNull(FETCH_TIMEOUT_MS) { runCatching { repository.stopInfo(id) }.getOrNull() }
            val nowMs = System.currentTimeMillis()
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
            val departures = info?.let {
                withContext(Dispatchers.Default) { stopDepartures(it, null, now, nowMs) }
            }.orEmpty()
            // Timetable only: skip runs already due.
            val runs = departures.flatMap { departure -> departure.nextTimes.map { departure to it.minutes } }.sortedBy { it.second }
            val next = runs.firstOrNull { it.second >= 1 } ?: runs.firstOrNull()
            NextAtStop(id, names[id] ?: info?.name.orEmpty(), next?.first, next?.second, nowMs)
        }
    }.awaitAll()
}

class NearStop(val stopId: Int, val name: String, val nearby: Boolean, val departures: List<StopDeparture>, val nowMs: Long)

suspend fun nextNearby(context: Context, count: Int): NearStop? {
    val repository = Services.repository
    repository.loadCached()
    val index = repository.network.index.value ?: return null
    val near = UserLocation.recent(context)?.let { nearestStation(index, it.lat, it.lon) }?.first
    val stop = near ?: Services.favorites.stops.value.firstNotNullOfOrNull { index.platforms(it).firstOrNull() } ?: return null
    val platforms = index.platforms(stop.stopId).map { it.stopId }
    val live = withTimeoutOrNull(FETCH_TIMEOUT_MS) { LiveStream.once(platforms, emptyList()) }
    val nowMs = System.currentTimeMillis()
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
    val departures = withContext(Dispatchers.Default) {
        stationDepartures(index.stationInfos(stop.stopId, nowMs), live, now, nowMs).take(count)
    }
    return NearStop(stop.stopId, stop.name, near != null, departures, nowMs)
}

fun etaLabel(context: Context, minutes: Int, nowMs: Long, scheduled: Boolean): String {
    val text = if (minutes in 1..59) context.getString(R.string.eta_minutes, minutes)
    else formatMinutesFromNow(minutes, LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE), context.getString(R.string.now))
    return if (scheduled) "~$text" else text
}

fun requestSurfaceUpdates(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(BusTileService::class.java) }
    runCatching {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, NextBusComplicationService::class.java))
            .requestUpdateAll()
    }
}

suspend fun updateSurfacesOnChange(context: Context) {
    combine(FollowManager.follows, Services.favorites.stops) { follows, stops -> follows.map { it.id to it.minutes } to stops }
        .distinctUntilChanged()
        .drop(1)
        .collect { requestSurfaceUpdates(context) }
}
