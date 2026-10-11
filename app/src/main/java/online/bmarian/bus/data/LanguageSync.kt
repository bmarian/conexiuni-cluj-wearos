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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import online.bmarian.bus.Services

object LanguageSync {
    const val PATH = "/sync/language"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Choice(val tag: String, val at: Long)

    private fun prefs(context: Context) = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    fun choose(context: Context, tag: String) {
        prefs(context).edit { putLong("at", System.currentTimeMillis()) }
        Services.setLanguage(context, tag)
    }

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
        Choice(map.getString("locale") ?: return null, map.getLong("at"))
    }.getOrNull()

    private fun take(context: Context, choice: Choice) {
        if (choice.tag != "ro" && choice.tag != "en") return
        val prefs = prefs(context)
        if (choice.at <= prefs.getLong("at", 0)) return
        prefs.edit { putLong("at", choice.at) }
        Services.setLanguage(context, choice.tag)
    }
}
