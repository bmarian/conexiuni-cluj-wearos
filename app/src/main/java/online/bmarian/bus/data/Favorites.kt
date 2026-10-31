package online.bmarian.bus.data

import android.content.Context
import android.content.SharedPreferences
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
import online.bmarian.bus.data.sync.SyncRecords

@Serializable
data class FavoriteRoute(val routeId: Int, val direction: String)

// Same keys as the website's localStorage; one file per city.
class Favorites(
    private val context: Context,
    private val onChange: (city: String, stops: List<Int>, routes: List<FavoriteRoute>, updatedAt: Long) -> Unit = { _, _, _, _ -> },
) {
    var city = Cities.current.value
        private set
    private var prefs = cityPrefs(context, "favorites", city)

    private val _routes = MutableStateFlow(parseFavoriteRoutes(read(prefs, "routes")))
    val routes: StateFlow<List<FavoriteRoute>> = _routes
    private val _stops = MutableStateFlow(parseStopIds(read(prefs, "stops")))
    val stops: StateFlow<List<Int>> = _stops

    private fun read(prefs: SharedPreferences, key: String): JsonElement? =
        prefs.getString(key, null)?.let { runCatching { Api.json.parseToJsonElement(it) }.getOrNull() }

    private fun prefsOf(city: String) = if (city == this.city) prefs else cityPrefs(context, "favorites", city)

    @Synchronized
    fun use(slug: String) {
        if (slug == city) return
        city = slug
        prefs = cityPrefs(context, "favorites", slug)
        _routes.value = parseFavoriteRoutes(read(prefs, "routes"))
        _stops.value = parseStopIds(read(prefs, "stops"))
    }

    private fun write(prefs: SharedPreferences, stops: List<Int>, routes: List<FavoriteRoute>, syncedAt: Long) {
        prefs.edit {
            putString("routes", Api.json.encodeToString(routes))
            putString("stops", Api.json.encodeToString(stops))
            putLong("syncedAt", syncedAt)
        }
    }

    private fun changed() {
        val now = System.currentTimeMillis()
        write(prefs, _stops.value, _routes.value, now)
        onChange(city, _stops.value, _routes.value, now)
    }

    fun isRouteFavorite(routeId: Int, direction: String? = null) =
        _routes.value.any { it.routeId == routeId && (direction == null || it.direction == direction) }

    @Synchronized
    fun toggleStop(stopId: Int) {
        if (stopId !in _stops.value && _stops.value.size >= SyncRecords.CITY_CAP) return
        _stops.value = if (stopId in _stops.value) _stops.value - stopId else _stops.value + stopId
        changed()
    }

    /** Devices may have saved different platforms of one station: every one of them goes. */
    @Synchronized
    fun toggleStation(stopId: Int, platforms: List<Int>) {
        val saved = (platforms + stopId).filter { it in _stops.value }.toSet()
        if (saved.isEmpty()) return toggleStop(stopId)
        _stops.value -= saved
        changed()
    }

    @Synchronized
    fun toggleRoute(routeId: Int, direction: String) {
        val fav = FavoriteRoute(routeId, direction)
        if (fav !in _routes.value && _routes.value.size >= SyncRecords.CITY_CAP) return
        _routes.value = if (fav in _routes.value) _routes.value - fav else _routes.value + fav
        changed()
    }

    fun savedIn(city: String): Pair<List<Int>, List<FavoriteRoute>> {
        val prefs = prefsOf(city)
        return parseStopIds(read(prefs, "stops")) to parseFavoriteRoutes(read(prefs, "routes"))
    }

    /** What sync agreed on. */
    @Synchronized
    fun replaceSynced(city: String, stops: List<Int>, routes: List<FavoriteRoute>) {
        if (savedIn(city) == stops to routes) return
        val now = System.currentTimeMillis()
        write(prefsOf(city), stops, routes, now)
        if (city == this.city) {
            _stops.value = stops
            _routes.value = routes
        }
        onChange(city, stops, routes, now)
    }

    @Synchronized
    fun takeRemote(city: String, stops: List<Int>, routes: List<FavoriteRoute>, updatedAt: Long) {
        val prefs = prefsOf(city)
        if (updatedAt <= prefs.getLong("syncedAt", 0)) return
        write(prefs, stops, routes, updatedAt)
        if (city != this.city) return
        _stops.value = stops
        _routes.value = routes
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
