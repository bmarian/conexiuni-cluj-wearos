package online.bmarian.bus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Recent(val stopId: Int? = null, val routeId: Int? = null, val label: String, val shortName: String? = null, val color: String? = null)

private const val KEPT = 4

object Recents {
    private val _all = MutableStateFlow<List<Recent>>(emptyList())
    val all: StateFlow<List<Recent>> = _all

    private fun prefs(context: Context) = cityPrefs(context, "recents", Cities.current.value)

    fun load(context: Context) {
        _all.value = prefs(context).getString("list", null)
            ?.let { runCatching { Api.json.decodeFromString<List<Recent>>(it) }.getOrNull() }
            .orEmpty()
    }

    fun add(context: Context, recent: Recent) {
        val list = (listOf(recent) + _all.value.filterNot { it.stopId == recent.stopId && it.routeId == recent.routeId }).take(KEPT)
        _all.value = list
        prefs(context).edit { putString("list", Api.json.encodeToString(list)) }
    }
}
