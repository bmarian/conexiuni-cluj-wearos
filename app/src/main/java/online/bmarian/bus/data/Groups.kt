package online.bmarian.bus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import online.bmarian.bus.logic.GroupLine
import online.bmarian.bus.logic.groupKey

/** Same shape as the phone's SavedGroup. */
@Serializable
data class SavedGroup(val lines: List<GroupLine>, val name: String? = null) {
    val key get() = groupKey(lines)
}

/** The line groups saved on the phone or the web, one list per city; the watch only shows them. */
class Groups(private val context: Context) {
    private var city = Cities.current.value
    private val _saved = MutableStateFlow(read(city))
    val saved: StateFlow<List<SavedGroup>> = _saved

    private fun read(city: String): List<SavedGroup> = cityPrefs(context, "groups", city).getString("list", null)
        ?.let { runCatching { Api.json.decodeFromString<List<SavedGroup>>(it) }.getOrNull() }
        .orEmpty()
        .filter { it.lines.isNotEmpty() }

    fun savedIn(city: String): List<SavedGroup> = if (city == this.city) _saved.value else read(city)

    @Synchronized
    fun use(slug: String) {
        if (slug == city) return
        city = slug
        _saved.value = read(slug)
    }

    @Synchronized
    fun take(city: String, list: List<SavedGroup>) {
        val kept = list.filter { it.lines.isNotEmpty() }
        cityPrefs(context, "groups", city).edit { putString("list", Api.json.encodeToString(kept)) }
        if (city == this.city) _saved.value = kept
    }
}
