package online.bmarian.bus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import online.bmarian.bus.logic.haversineMeters

@Serializable
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
}

const val MAP_CREDITS = "Â© OpenMapTiles Â© OpenStreetMap"

@Serializable
data class Credit(val name: String = "", val url: String = "")

/** One entry of /api/cities. */
@Serializable
data class City(
    val slug: String,
    val name: String,
    val operator: String = "",
    val operatorUrl: String = "",
    val realtime: Boolean = false,
    val news: Boolean = false,
    val lat: Double,
    val lon: Double,
    val bounds: Bounds? = null,
    val credits: List<Credit> = emptyList(),
) {
    /** The map's data credits: the sources, then the operator. */
    val creditsText: String
        get() = "$MAP_CREDITS | " + (credits.map { it.name } + operator).filter { it.isNotEmpty() }.joinToString(", ") { "Â© $it" }
}

object Cities {
    const val DEFAULT = "cluj"
    private val SLUG = Regex("^[a-z][a-z0-9-]*$")

    private lateinit var prefs: SharedPreferences

    private val _list = MutableStateFlow<List<City>>(emptyList())
    val list: StateFlow<List<City>> = _list
    private val _current = MutableStateFlow(DEFAULT)
    val current: StateFlow<String> = _current

    fun init(context: Context) {
        prefs = context.getSharedPreferences("cities", Context.MODE_PRIVATE)
        _list.value = prefs.getString("list", null)
            ?.let { runCatching { Api.json.decodeFromString<List<City>>(it) }.getOrNull() }
            .orEmpty()
        _current.value = listOfNotNull(prefs.getString("phone", null), prefs.getString("last", null)).firstOrNull(::known) ?: DEFAULT
    }

    fun isSlug(text: String) = SLUG.matches(text)

    fun known(slug: String) = isSlug(slug) && (_list.value.isEmpty() || _list.value.any { it.slug == slug })

    fun info(slug: String = current.value): City? = _list.value.firstOrNull { it.slug == slug }

    fun contains(lat: Double, lon: Double, slug: String = current.value): Boolean = info(slug)?.bounds?.contains(lat, lon) ?: true

    suspend fun reload(): List<City> {
        val fresh = runCatching { Api.getShared<List<City>>("cities") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return _list.value
        _list.value = fresh
        prefs.edit { putString("list", Api.json.encodeToString(fresh)) }
        return fresh
    }

    fun resolve(fix: Fix?): String {
        prefs.getString("phone", null)?.takeIf(::known)?.let { return it }
        val fallback = listOfNotNull(prefs.getString("last", null), DEFAULT).firstOrNull(::known) ?: _list.value.firstOrNull()?.slug ?: DEFAULT
        if (_list.value.size < 2 || fix == null) return fallback
        return _list.value.minByOrNull { haversineMeters(fix.lat, fix.lon, it.lat, it.lon) }?.slug ?: fallback
    }

    fun takePhone(slug: String, at: Long): Boolean {
        if (!isSlug(slug) || at <= prefs.getLong("phoneAt", 0)) return false
        prefs.edit {
            putString("phone", slug)
            putLong("phoneAt", at)
        }
        return true
    }

    internal fun show(slug: String) {
        prefs.edit { putString("last", slug) }
        _current.value = slug
    }
}

fun cityPrefs(context: Context, name: String, city: String): SharedPreferences {
    val prefs = context.getSharedPreferences("$name.$city", Context.MODE_PRIVATE)
    if (city == Cities.DEFAULT && prefs.all.isEmpty()) {
        val old = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        if (old.isNotEmpty()) {
            prefs.edit(commit = true) {
                for ((key, value) in old) when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            context.deleteSharedPreferences(name)
        }
    }
    return prefs
}
