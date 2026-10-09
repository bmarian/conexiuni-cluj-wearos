package online.bmarian.bus.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class VehicleMerge(private val tripIds: List<String>) {
    private val byId = LinkedHashMap<Int, JsonObject>()

    fun apply(type: String?, data: String): Map<String, List<Vehicle>>? {
        val root = runCatching { Api.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        when (type) {
            "snapshot" -> {
                byId.clear()
                for (v in root["vehicles"]?.jsonArray.orEmpty()) put(v.jsonObject)
            }
            "delta" -> {
                for (v in root["upsert"]?.jsonArray.orEmpty()) put(v.jsonObject)
                for (id in root["remove"]?.jsonArray.orEmpty()) runCatching { byId.remove(id.jsonPrimitive.int) }
            }
            else -> return null
        }
        return grouped()
    }

    private fun grouped(): Map<String, List<Vehicle>> {
        val grouped = tripIds.associateWith { mutableListOf<Vehicle>() }
        for (v in byId.values) {
            val vehicle = runCatching { Api.json.decodeFromJsonElement(Vehicle.serializer(), v) }.getOrNull() ?: continue
            grouped[vehicle.tripId]?.add(vehicle)
        }
        return grouped
    }

    private fun put(update: JsonObject) {
        val id = update["id"]?.jsonPrimitive?.intOrNull ?: return
        byId[id] = JsonObject(byId[id].orEmpty() + update)
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

object VehicleStream {
    // The server pings every 25 s.
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
            .url(Api.url("vehicles/stream?v=2&trip_ids=$query"))
            .header("Accept", "text/event-stream")
            .build()
        val merge = VehicleMerge(ids)
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
