package online.bmarian.bus.presentation.theme

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
import kotlin.math.roundToInt

/** Port of the phone's LineColors.kt. */
fun hashedLineColor(name: String): String {
    var hash = 0x811c9dc5.toInt()
    for (byte in name.trim().uppercase().toByteArray()) hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
    val h = hash.toUInt()
    return hslToHex((h % 360u).toInt(), 66 + ((h shr 9) % 16u).toInt(), 44 + ((h shr 17) % 12u).toInt())
}

fun manyLines(names: List<String>) = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet().size > 1

@Immutable
data class LineLooks(private val colorful: Boolean, private val favorites: Set<String>?) {
    fun hex(name: String?, hex: String?, apart: Boolean = false): String? =
        if (!name.isNullOrBlank() && (apart || colorful || favorite(name) == true)) hashedLineColor(name) else hex

    /** Null until the favourites are known, so loading never animates. */
    fun favorite(name: String?): Boolean? = favorites?.contains(name)

    fun color(name: String?, hex: String?, apart: Boolean = false): Color = routeColor(hex(name, hex, apart).orEmpty())
}

object LineColors {
    val looks: StateFlow<LineLooks> by lazy {
        val favorites = combine(Services.favorites.routes, Services.repository.routes) { favorites, routes ->
            if (routes.isEmpty()) null else favorites.mapNotNull { f -> routes.firstOrNull { it.routeId == f.routeId }?.shortName }.toSet()
        }
        combine(ColorSync.colorful, favorites, ::LineLooks)
            .stateIn(Services.scope, SharingStarted.Eagerly, LineLooks(ColorSync.colorful.value, null))
    }
}

fun ownHex(name: String?, hex: String?): String? = LineColors.looks.value.hex(name, hex)

val LocalLineLooks = compositionLocalOf { LineLooks(colorful = false, favorites = null) }

@Composable
fun lineColor(name: String?, hex: String?, apart: Boolean = false): Color = LocalLineLooks.current.color(name, hex, apart)

private fun hslToHex(h: Int, s: Int, l: Int): String {
    val sf = s / 100.0
    val lf = l / 100.0
    val q = if (lf < 0.5) lf * (1 + sf) else lf + sf - lf * sf
    val p = 2 * lf - q
    val hue = h / 360.0
    return "#" + listOf(hue + 1.0 / 3, hue, hue - 1.0 / 3).joinToString("") { "%02X".format((hueToRgb(p, q, it) * 255).roundToInt()) }
}

private fun hueToRgb(p: Double, q: Double, at: Double): Double {
    val t = if (at < 0) at + 1 else if (at > 1) at - 1 else at
    return when {
        t < 1.0 / 6 -> p + (q - p) * 6 * t
        t < 1.0 / 2 -> q
        t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
        else -> p
    }
}
