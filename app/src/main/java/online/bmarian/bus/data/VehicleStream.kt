package online.bmarian.bus.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Live vehicles for some trips, grouped by trip. Reconnects until the collector stops. */
object VehicleStream {
    // The server pings every 25 s, so a minute of silence means the connection is gone.
    // No cache: it would try to store the never-ending body.
    private val client: OkHttpClient by lazy {
        Api.client.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun vehicles(tripIds: List<String>): Flow<Map<String, List<Vehicle>>> = flow {
        val ids = tripIds.distinct().sorted()
        if (ids.isEmpty()) {
            emit(emptyMap())
            return@flow
        }
        var backoff = 2_000L
        while (true) {
            try {
                connection(ids).collect {
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

    private fun connection(ids: List<String>): Flow<Map<String, List<Vehicle>>> = callbackFlow {
        val query = URLEncoder.encode(ids.joinToString(","), "UTF-8")
        val request = Request.Builder()
            .url(Api.url("vehicles/stream?trip_ids=$query"))
            .header("Accept", "text/event-stream")
            .build()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val raw = runCatching { Api.json.decodeFromString<List<Vehicle>>(data) }.getOrNull() ?: return
                val grouped = ids.associateWith { mutableListOf<Vehicle>() }
                for (v in raw) grouped[v.tripId]?.add(v)
                trySend(grouped)
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
