package online.bmarian.bus.data

import android.content.Context
import androidx.core.content.edit
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** The phone's "Colorful lines" setting. */
object ColorSync {
    const val PATH = "/sync/colors"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _colorful = MutableStateFlow(false)
    val colorful: StateFlow<Boolean> = _colorful

    private class Choice(val colorful: Boolean, val at: Long)

    private fun prefs(context: Context) = context.getSharedPreferences("colors", Context.MODE_PRIVATE)

    fun pull(context: Context) {
        val app = context.applicationContext
        _colorful.value = prefs(app).getBoolean("colorful", false)
        scope.launch {
            runCatching {
                val items = Wearable.getDataClient(app).getDataItems("wear://*$PATH".toUri()).await()
                val newest = try {
                    items.mapNotNull(::read).maxByOrNull { it.at }
                } finally {
                    items.release()
                }
                newest?.let { take(app, it) }
            }
        }
    }

    fun onDataChanged(context: Context, events: DataEventBuffer) {
        val local = runCatching { Tasks.await(Wearable.getNodeClient(context).localNode, 5, TimeUnit.SECONDS).id }.getOrNull()
        events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH && it.dataItem.uri.host != local }
            .mapNotNull { read(it.dataItem) }
            .maxByOrNull { it.at }
            ?.let { take(context, it) }
    }

    private fun read(item: DataItem): Choice? = runCatching {
        val map = DataMapItem.fromDataItem(item).dataMap
        Choice(map.getBoolean("colorful"), map.getLong("at"))
    }.getOrNull()

    private fun take(context: Context, choice: Choice) {
        val prefs = prefs(context)
        if (choice.at <= prefs.getLong("at", 0)) return
        prefs.edit {
            putLong("at", choice.at)
            putBoolean("colorful", choice.colorful)
        }
        _colorful.value = choice.colorful
    }
}
