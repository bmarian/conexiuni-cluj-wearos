package online.bmarian.bus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class FavoriteRoute(val routeId: Int, val direction: String)

// Same keys as the website's localStorage.
class Favorites(
    context: Context,
    private val onChange: (stops: List<Int>, routes: List<FavoriteRoute>, updatedAt: Long) -> Unit = { _, _, _ -> },
) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private val _routes = MutableStateFlow(parseFavoriteRoutes(read("routes")))
    val routes: StateFlow<List<FavoriteRoute>> = _routes
    private val _stops = MutableStateFlow(parseStopIds(read("stops")))
    val stops: StateFlow<List<Int>> = _stops

    private fun read(key: String): JsonElement? =
        prefs.getString(key, null)?.let { runCatching { Api.json.parseToJsonElement(it) }.getOrNull() }

    private var syncedAt: Long
        get() = prefs.getLong("syncedAt", 0)
        set(value) = prefs.edit { putLong("syncedAt", value) }

    private fun persist() {
        prefs.edit {
            putString("routes", Api.json.encodeToString(_routes.value))
            putString("stops", Api.json.encodeToString(_stops.value))
        }
    }

    private fun changed() {
        persist()
        val now = System.currentTimeMillis()
        syncedAt = now
        onChange(_stops.value, _routes.value, now)
    }

    fun isStopFavorite(stopId: Int) = stopId in _stops.value

    fun isRouteFavorite(routeId: Int, direction: String? = null) =
        _routes.value.any { it.routeId == routeId && (direction == null || it.direction == direction) }

    @Synchronized
    fun toggleStop(stopId: Int) {
        _stops.value = if (stopId in _stops.value) _stops.value - stopId else _stops.value + stopId
        changed()
    }

    @Synchronized
    fun toggleRoute(routeId: Int, direction: String) {
        val fav = FavoriteRoute(routeId, direction)
        _routes.value = if (fav in _routes.value) _routes.value - fav else _routes.value + fav
        changed()
    }

    @Synchronized
    fun import(favorites: JsonObject) {
        _routes.value = parseFavoriteRoutes(favorites["routes"])
        _stops.value = parseStopIds(favorites["stops"])
        changed()
    }

    @Synchronized
    fun takeRemote(stops: List<Int>, routes: List<FavoriteRoute>, updatedAt: Long) {
        if (updatedAt <= syncedAt) return
        _stops.value = stops
        _routes.value = routes
        persist()
        syncedAt = updatedAt
    }

    companion object {
        fun parseFavoriteRoutes(raw: JsonElement?): List<FavoriteRoute> {
            val parsed = mutableListOf<FavoriteRoute>()
            for (item in raw as? JsonArray ?: return emptyList()) {
                val fav = when (item) {
                    // Legacy entries are bare route ids.
                    is JsonPrimitive -> item.intOrNull?.takeIf { !item.isString }?.let { FavoriteRoute(it, "0") }
                    is JsonObject -> {
                        val id = (item["routeId"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                        val direction = (item["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        if (id != null && (direction == "0" || direction == "1")) FavoriteRoute(id, direction) else null
                    }
                    else -> null
                }
                if (fav != null && fav !in parsed) parsed += fav
            }
            return parsed
        }

        fun parseStopIds(raw: JsonElement?): List<Int> {
            val list = raw as? JsonArray ?: return emptyList()
            return list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull }
        }
    }
}
