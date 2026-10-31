package online.bmarian.bus.data

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** The phone's recent stops and lines, one data item per city. The phone owns the list; the watch sends it what it opens. */
object RecentsSync {
    const val PATH = "/sync/recents"
    private const val KEY = "json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun pull(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val local = Wearable.getNodeClient(app).localNode.await().id
                val items = Wearable.getDataClient(app).getDataItems("wear://*$PATH".toUri(), DataClient.FILTER_PREFIX).await()
                val found = try {
                    items.filter { it.uri.host != local }.mapNotNull(::read)
                } finally {
                    items.release()
                }
                found.forEach { (city, list) -> Recents.take(app, city, list) }
            }
        }
    }

    fun onDataChanged(context: Context, events: DataEventBuffer) {
        val local = runCatching { Tasks.await(Wearable.getNodeClient(context).localNode, 5, TimeUnit.SECONDS).id }.getOrNull()
        events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.host != local }
            .mapNotNull { read(it.dataItem) }
            .forEach { (city, list) -> Recents.take(context, city, list) }
    }

    private fun read(item: DataItem): Pair<String, List<Recent>>? {
        val city = item.uri.path?.takeIf { it.startsWith("$PATH/") }?.removePrefix("$PATH/")?.takeIf(Cities::isSlug) ?: return null
        val json = runCatching { DataMapItem.fromDataItem(item).dataMap.getString(KEY) }.getOrNull() ?: return null
        return runCatching { city to Api.json.decodeFromString<List<Recent>>(json) }.getOrNull()
    }
}
