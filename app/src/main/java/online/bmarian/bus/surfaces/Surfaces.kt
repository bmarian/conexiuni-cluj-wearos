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
import online.bmarian.bus.logic.computeStopDepartures
import online.bmarian.bus.logic.formatMinutesFromNow
import java.time.Instant
import java.time.LocalDateTime

private const val FETCH_TIMEOUT_MS = 5_000L

/** A favorite stop and its next departure, as the tile and the complication show it. */
class NextAtStop(val stopId: Int, val name: String, val departure: StopDeparture?, val minutes: Int?, val nowMs: Long)

/**
 * The next departure at each of the first [count] favorite stops. From the timetable only, as a
 * glance has to answer at once: a stop opened before comes from the cache, the rest from the API.
 */
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
                withContext(Dispatchers.Default) { computeStopDepartures(it, emptyMap(), emptyMap(), now, nowMs) }
            }.orEmpty()
            // Without live buses a run up to a few minutes late still reads "now", and has
            // likely gone: a glance shows the first one still to come.
            val runs = departures.flatMap { departure -> departure.nextTimes.map { departure to it.minutes } }.sortedBy { it.second }
            val next = runs.firstOrNull { it.second >= 1 } ?: runs.firstOrNull()
            NextAtStop(id, names[id] ?: info?.name.orEmpty(), next?.first, next?.second, nowMs)
        }
    }.awaitAll()
}

/** "5 min", or the time once it's an hour away; "~" in front when it is only the timetable's. */
fun etaLabel(context: Context, minutes: Int, nowMs: Long, scheduled: Boolean): String {
    val text = if (minutes in 1..59) context.getString(R.string.eta_minutes, minutes)
    else formatMinutesFromNow(minutes, LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE), context.getString(R.string.now))
    return if (scheduled) "~$text" else text
}

/** Asks the tile and the complication to show what changed. */
fun requestSurfaceUpdates(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(BusTileService::class.java) }
    runCatching {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, NextBusComplicationService::class.java))
            .requestUpdateAll()
    }
}

/** Keeps them in step with the followed bus and the favorite stops while the app runs. */
suspend fun updateSurfacesOnChange(context: Context) {
    combine(FollowManager.state, Services.favorites.stops) { follow, stops -> Triple(follow?.id, follow?.minutes, stops) }
        .distinctUntilChanged()
        // What they showed when the app started is already up.
        .drop(1)
        .collect { requestSurfaceUpdates(context) }
}
