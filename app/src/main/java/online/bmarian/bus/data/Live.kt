package online.bmarian.bus.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

// Predicted arrival as unix seconds.
@Serializable
data class LiveEntry(
    val at: Long,
    val live: Boolean = false,
    val soon: Boolean = false,
    @SerialName("vehicle_id") val vehicleId: Int = 0,
)

class ShownEntry(val minutes: Int, val live: Boolean, val soon: Boolean, val vehicleId: Int)

@Serializable
data class LiveRow(
    @SerialName("trip_id") val tripId: String,
    @SerialName("stop_id") val stopId: Int,
    val entries: List<LiveEntry> = emptyList(),
)

@Serializable
data class LiveSnapshot(
    @SerialName("as_of") val asOf: Long,
    val rows: List<LiveRow> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
)

@Serializable
private data class LiveVehicleDelta(val upsert: List<Vehicle> = emptyList(), val remove: List<Int> = emptyList())

@Serializable
private data class LiveDelta(
    @SerialName("as_of") val asOf: Long,
    val rows: List<LiveRow> = emptyList(),
    val vehicles: LiveVehicleDelta = LiveVehicleDelta(),
)

fun liveRowKey(tripId: String, stopId: Int) = "$tripId@$stopId"

class LiveState(val asOfMs: Long, val rows: Map<String, LiveRow>, val vehiclesByTrip: Map<String, List<Vehicle>>) {
    /** The minutes a screen shows for the row's entries at [nowMs]; null without a row. */
    fun entriesAt(tripId: String, stopId: Int, nowMs: Long): List<ShownEntry>? {
        val row = rows[liveRowKey(tripId, stopId)] ?: return null
        return row.entries.map {
            ShownEntry(((it.at * 1000 - nowMs) / 60_000.0).roundToInt().coerceAtLeast(0), it.live, it.soon, it.vehicleId)
        }
    }

    fun vehicles(tripId: String): List<Vehicle> = vehiclesByTrip[tripId].orEmpty()

    /** The other direction's buses that [tripId]'s rows count on coming back. */
    fun roundTrip(tripId: String, otherTripId: String): List<Vehicle> {
        val counted = rows.values.filter { it.tripId == tripId }.flatMap { row -> row.entries.map { it.vehicleId } }.toSet()
        return vehicles(otherTripId).filter { it.id in counted }
    }
}

class LiveMerge {
    private val rows = LinkedHashMap<String, LiveRow>()
    private val vehicles = LinkedHashMap<Int, Vehicle>()
    private var asOfMs = 0L

    fun apply(snapshot: LiveSnapshot): LiveState {
        rows.clear()
        vehicles.clear()
        snapshot.rows.forEach { rows[liveRowKey(it.tripId, it.stopId)] = it }
        snapshot.vehicles.forEach { vehicles[it.id] = it }
        asOfMs = snapshot.asOf * 1000
        return state()
    }

    private fun state() = LiveState(asOfMs, rows.toMap(), vehicles.values.groupBy { it.tripId.orEmpty() })

    fun apply(type: String?, data: String): LiveState? {
        when (type) {
            "snapshot" -> return apply(runCatching { Api.json.decodeFromString<LiveSnapshot>(data) }.getOrNull() ?: return null)
            "delta" -> {
                val delta = runCatching { Api.json.decodeFromString<LiveDelta>(data) }.getOrNull() ?: return null
                delta.rows.forEach { rows[liveRowKey(it.tripId, it.stopId)] = it }
                delta.vehicles.upsert.forEach { vehicles[it.id] = it }
                delta.vehicles.remove.forEach { vehicles.remove(it) }
                asOfMs = delta.asOf * 1000
            }
            else -> return null
        }
        return state()
    }
}

object LiveStream {
    // The server pings every 25 s.
    private val client: OkHttpClient by lazy {
        Api.client.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun query(stopIds: List<Int>, tripIds: List<String>): String {
        val parts = mutableListOf<String>()
        if (stopIds.isNotEmpty()) parts += "stop_ids=" + stopIds.distinct().sorted().joinToString(",")
        if (tripIds.isNotEmpty()) parts += "trip_ids=" + tripIds.distinct().sorted().joinToString(",")
        return parts.joinToString("&")
    }

    /** One reading of the rows and vehicles, for surfaces that cannot hold a stream open. */
    suspend fun once(stopIds: List<Int>, tripIds: List<String>, city: String = Cities.current.value): LiveState? {
        val q = query(stopIds, tripIds)
        if (q.isEmpty()) return null
        val snapshot = runCatching { Api.getFresh<LiveSnapshot>("live?$q&once=1", city) }.getOrNull() ?: return null
        return LiveMerge().apply(snapshot)
    }

    fun live(stopIds: List<Int>, tripIds: List<String>, city: String = Cities.current.value): Flow<LiveState> = flow {
        val q = query(stopIds, tripIds)
        if (q.isEmpty()) return@flow
        var backoff = 2_000L
        while (true) {
            try {
                connection(q, city).collect {
                    emit(it)
                    backoff = 2_000L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private fun connection(q: String, city: String): Flow<LiveState> = callbackFlow {
        val request = Request.Builder().url(Api.url("live?$q", city)).header("Accept", "text/event-stream").build()
        val merge = LiveMerge()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                merge.apply(type, data)?.let { trySend(it) }
            }

            override fun onClosed(eventSource: EventSource) {
                close(IOException("stream closed"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("stream failed: ${response?.code}"))
            }
        })
        awaitClose { source.cancel() }
    }
}
