package online.bmarian.bus.data

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
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

/**
 * Keeps the favorites the same as the phone app's, over the Wear OS data layer. Each device
 * writes its own copy when the user changes them there, and the newest copy wins on both. The
 * phone app writes and reads the same item.
 */
object FavoritesSync {
    const val PATH = "/sync/favorites"
    private const val KEY = "json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Both lists in display order, and when they were changed, in epoch milliseconds. */
    @Serializable
    data class Snapshot(val stops: List<Int>, val routes: List<FavoriteRoute>, val updatedAt: Long)

    fun encode(snapshot: Snapshot): String = Api.json.encodeToString(snapshot)

    /** What another device wrote; anything it can't read is dropped, as an import does. */
    fun decode(json: String): Snapshot? = runCatching {
        val root = Api.json.parseToJsonElement(json).jsonObject
        Snapshot(
            stops = Favorites.parseStopIds(root["stops"]),
            routes = Favorites.parseFavoriteRoutes(root["routes"]),
            updatedAt = root["updatedAt"]?.jsonPrimitive?.longOrNull ?: return null,
        )
    }.getOrNull()

    /** A change made here, for the phone to take. */
    fun publish(context: Context, stops: List<Int>, routes: List<FavoriteRoute>, updatedAt: Long) {
        val app = context.applicationContext
        val json = encode(Snapshot(stops, routes, updatedAt))
        PhoneLink.relay(PATH, json.encodeToByteArray())
        scope.launch {
            // A watch without a phone has no one to tell; the change still stays here.
            runCatching {
                val request = PutDataMapRequest.create(PATH)
                    .apply { dataMap.putString(KEY, json) }
                    .asPutDataRequest()
                    .setUrgent()
                Wearable.getDataClient(app).putDataItem(request).await()
            }
        }
    }

    /**
     * At start: what the phone changed while the app wasn't running. Never sends anything, so a
     * fresh install doesn't wipe the phone's favorites with its empty lists.
     */
    fun pull(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val local = Wearable.getNodeClient(app).localNode.await().id
                val items = Wearable.getDataClient(app).getDataItems("wear://*$PATH".toUri()).await()
                val newest = try {
                    items.filter { it.uri.host != local }.mapNotNull(::read).maxByOrNull { it.updatedAt }
                } finally {
                    items.release()
                }
                newest?.let(::take)
            }
        }
    }

    /** The phone's copy changed. Runs on the listener's own thread, which may wait. */
    fun onDataChanged(context: Context, events: DataEventBuffer) {
        // Our own writes come back here too. Without the id they are still skipped below, as
        // their time is the one already saved.
        val local = runCatching { Tasks.await(Wearable.getNodeClient(context).localNode, 5, TimeUnit.SECONDS).id }.getOrNull()
        val newest = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH && it.dataItem.uri.host != local }
            .mapNotNull { read(it.dataItem) }
            .maxByOrNull { it.updatedAt }
        newest?.let(::take)
    }

    private fun read(item: DataItem): Snapshot? =
        runCatching { DataMapItem.fromDataItem(item).dataMap.getString(KEY) }.getOrNull()?.let(::decode)

    /** The phone's favorites as JSON, from the debug relay. */
    internal fun onRemoteJson(json: String) {
        decode(json)?.let(::take)
    }

    private fun take(snapshot: Snapshot) {
        Services.favorites.takeRemote(snapshot.stops, snapshot.routes, snapshot.updatedAt)
    }
}
