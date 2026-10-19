package online.bmarian.bus.data

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import online.bmarian.bus.Services
import online.bmarian.bus.follow.PhoneLink

/** Shared with the phone app, one data item per city; the newest copy wins. */
object FavoritesSync {
    // Cluj's; other cities' under it, as /sync/favorites/{city}. Older builds know only Cluj's.
    const val PATH = "/sync/favorites"
    private const val KEY = "json"

    fun path(city: String) = if (city == Cities.DEFAULT) PATH else "$PATH/$city"

    /** The city a favorites item belongs to; null for another path. */
    fun cityOf(path: String?): String? = when {
        path == PATH -> Cities.DEFAULT
        path != null && path.startsWith("$PATH/") -> path.removePrefix("$PATH/").takeIf(Cities::isSlug)
        else -> null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** updatedAt in epoch ms. */
    @Serializable
    data class Snapshot(val stops: List<Int>, val routes: List<FavoriteRoute>, val updatedAt: Long)

    fun encode(snapshot: Snapshot): String = Api.json.encodeToString(snapshot)

    fun decode(json: String): Snapshot? = runCatching {
        val root = Api.json.parseToJsonElement(json).jsonObject
        Snapshot(
            stops = Favorites.parseStopIds(root["stops"]),
            routes = Favorites.parseFavoriteRoutes(root["routes"]),
            updatedAt = root["updatedAt"]?.jsonPrimitive?.longOrNull ?: return null,
        )
    }.getOrNull()

    fun publish(context: Context, city: String, stops: List<Int>, routes: List<FavoriteRoute>, updatedAt: Long) {
        val app = context.applicationContext
        val json = encode(Snapshot(stops, routes, updatedAt))
        PhoneLink.relay(path(city), json.encodeToByteArray())
        scope.launch {
            runCatching {
                val request = PutDataMapRequest.create(path(city))
                    .apply { dataMap.putString(KEY, json) }
                    .asPutDataRequest()
                    .setUrgent()
                Wearable.getDataClient(app).putDataItem(request).await()
            }
        }
    }

    /** Never sends: a fresh install must not wipe the phone's favorites. */
    fun pull(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val local = Wearable.getNodeClient(app).localNode.await().id
                val items = Wearable.getDataClient(app).getDataItems("wear://*$PATH".toUri(), DataClient.FILTER_PREFIX).await()
                val newest = try {
                    items.filter { it.uri.host != local }.mapNotNull(::read)
                } finally {
                    items.release()
                }
                newest.groupBy({ it.first }, { it.second }).forEach { (city, list) -> take(city, list.maxBy { it.updatedAt }) }
            }
        }
    }

    fun onDataChanged(context: Context, events: DataEventBuffer) {
        // Own writes come back too; their time is already saved.
        val local = runCatching { Tasks.await(Wearable.getNodeClient(context).localNode, 5, TimeUnit.SECONDS).id }.getOrNull()
        events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.host != local }
            .mapNotNull { read(it.dataItem) }
            .groupBy({ it.first }, { it.second })
            .forEach { (city, list) -> take(city, list.maxBy { it.updatedAt }) }
    }

    private fun read(item: DataItem): Pair<String, Snapshot>? {
        val city = cityOf(item.uri.path) ?: return null
        val json = runCatching { DataMapItem.fromDataItem(item).dataMap.getString(KEY) }.getOrNull() ?: return null
        return decode(json)?.let { city to it }
    }

    internal fun onRemoteJson(json: String) {
        decode(json)?.let { take(Cities.DEFAULT, it) }
    }

    private fun take(city: String, snapshot: Snapshot) {
        Services.favorites.takeRemote(city, snapshot.stops, snapshot.routes, snapshot.updatedAt)
    }
}
