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

// Same shapes as favorites:routes and favorites:stops on the web, so an export reads the same.
class Favorites(context: Context, private val onChange: () -> Unit) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private val _routes = MutableStateFlow(parseFavoriteRoutes(read("routes")))
    val routes: StateFlow<List<FavoriteRoute>> = _routes
    private val _stops = MutableStateFlow(parseStopIds(read("stops")))
    val stops: StateFlow<List<Int>> = _stops

    private fun read(key: String): JsonElement? =
        prefs.getString(key, null)?.let { runCatching { Api.json.parseToJsonElement(it) }.getOrNull() }

    private fun persist() {
        prefs.edit {
            putString("routes", Api.json.encodeToString(_routes.value))
            putString("stops", Api.json.encodeToString(_stops.value))
        }
        onChange()
    }

    fun isStopFavorite(stopId: Int) = stopId in _stops.value

    fun isRouteFavorite(routeId: Int, direction: String) =
        _routes.value.any { it.routeId == routeId && it.direction == direction }

    fun toggleStop(stopId: Int) {
        _stops.value = if (stopId in _stops.value) _stops.value - stopId else _stops.value + stopId
        persist()
    }

    fun toggleRoute(routeId: Int, direction: String) {
        val fav = FavoriteRoute(routeId, direction)
        _routes.value = if (fav in _routes.value) _routes.value - fav else _routes.value + fav
        persist()
    }

    /** Replaces both lists with what another device sent, like importAll on the web. */
    fun import(favorites: JsonObject) {
        _routes.value = parseFavoriteRoutes(favorites["routes"])
        _stops.value = parseStopIds(favorites["stops"])
        persist()
    }

    companion object {
        fun parseFavoriteRoutes(raw: JsonElement?): List<FavoriteRoute> {
            val parsed = mutableListOf<FavoriteRoute>()
            for (item in raw as? JsonArray ?: return emptyList()) {
                val fav = when (item) {
                    // Favorites saved before directions existed are bare route ids.
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
