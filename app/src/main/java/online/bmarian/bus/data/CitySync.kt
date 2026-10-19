package online.bmarian.bus.data

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
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
import online.bmarian.bus.Services

/** The watch shows the phone's city. */
object CitySync {
    const val PATH = "/sync/city"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Choice(val city: String, val at: Long)

    fun pull(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val items = Wearable.getDataClient(app).getDataItems("wear://*$PATH".toUri()).await()
                val newest = try {
                    items.mapNotNull(::read).maxByOrNull { it.at }
                } finally {
                    items.release()
                }
                newest?.let(::take)
            }
        }
    }

    fun onDataChanged(context: Context, events: DataEventBuffer) {
        val local = runCatching { Tasks.await(Wearable.getNodeClient(context).localNode, 5, TimeUnit.SECONDS).id }.getOrNull()
        events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH && it.dataItem.uri.host != local }
            .mapNotNull { read(it.dataItem) }
            .maxByOrNull { it.at }
            ?.let(::take)
    }

    private fun read(item: DataItem): Choice? = runCatching {
        val map = DataMapItem.fromDataItem(item).dataMap
        Choice(map.getString("city") ?: return null, map.getLong("at"))
    }.getOrNull()

    private fun take(choice: Choice) {
        if (Cities.takePhone(choice.city, choice.at)) scope.launch(Dispatchers.Main) { Services.showCity(choice.city) }
    }
}
