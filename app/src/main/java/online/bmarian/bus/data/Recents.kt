package online.bmarian.bus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import online.bmarian.bus.follow.PhoneLink

@Serializable
data class Recent(val stopId: Int? = null, val routeId: Int? = null, val label: String, val shortName: String? = null, val color: String? = null)

private const val KEPT = 4

object Recents {
    private val _all = MutableStateFlow<List<Recent>>(emptyList())
    val all: StateFlow<List<Recent>> = _all

    private fun prefs(context: Context, city: String = Cities.current.value) = cityPrefs(context, "recents", city)

    fun load(context: Context) {
        _all.value = prefs(context).getString("list", null)
            ?.let { runCatching { Api.json.decodeFromString<List<Recent>>(it) }.getOrNull() }
            .orEmpty()
    }

    fun add(context: Context, recent: Recent) {
        val list = (listOf(recent) + _all.value.filterNot { it.stopId == recent.stopId && it.routeId == recent.routeId }).take(KEPT)
        _all.value = list
        prefs(context).edit { putString("list", Api.json.encodeToString(list)) }
        PhoneLink.sendRecent(context, recent)
    }

    /** What the phone has for [city]. */
    fun take(context: Context, city: String, phone: List<Recent>) {
        val list = phone.take(KEPT)
        prefs(context, city).edit { putString("list", Api.json.encodeToString(list)) }
        if (city == Cities.current.value) _all.value = list
    }
}
