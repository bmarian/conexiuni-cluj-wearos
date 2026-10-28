package online.bmarian.bus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import online.bmarian.bus.Services
import online.bmarian.bus.data.ColorSync

fun manyLines(names: List<String>) = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet().size > 1

/** Port of the phone's LineColors.kt. */
@Immutable
data class LineLooks(private val colorful: Boolean, private val favorites: Set<String>?, private val colorfuls: Map<String, String> = emptyMap()) {
    fun hex(name: String?, hex: String?, apart: Boolean = false): String? =
        if (!name.isNullOrBlank() && (apart || colorful || favorite(name) == true)) colorfuls[name] ?: hex else hex

    /** Null until the favourites are known, so loading never animates. */
    fun favorite(name: String?): Boolean? = favorites?.contains(name)

    fun color(name: String?, hex: String?, apart: Boolean = false): Color = routeColor(hex(name, hex, apart).orEmpty())
}

object LineColors {
    val looks: StateFlow<LineLooks> by lazy {
        val lines = combine(Services.favorites.routes, Services.repository.routes) { favorites, routes ->
            val names = favorites.mapNotNull { f -> routes.firstOrNull { it.routeId == f.routeId }?.shortName }.toSet()
            (if (routes.isEmpty()) null else names) to routes.filter { it.colorful.isNotEmpty() }.associate { it.shortName to it.colorful }
        }
        combine(ColorSync.colorful, lines) { on, (favorites, colorfuls) -> LineLooks(on, favorites, colorfuls) }
            .stateIn(Services.scope, SharingStarted.Eagerly, LineLooks(ColorSync.colorful.value, null))
    }
}

fun ownHex(name: String?, hex: String?): String? = LineColors.looks.value.hex(name, hex)

val LocalLineLooks = compositionLocalOf { LineLooks(colorful = false, favorites = null) }

@Composable
fun lineColor(name: String?, hex: String?, apart: Boolean = false): Color = LocalLineLooks.current.color(name, hex, apart)
