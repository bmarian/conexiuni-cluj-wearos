package online.bmarian.bus.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

// Map-only colours: the map draws on its own dark tiles.
object Palette {
    val Sub = Color(0xFF94A3B8)
    val Accent = Color(0xFF34D399)
    val AccentStrong = Color(0xFF10B981)
    val AccentDeep = Color(0xFF047857)
    val SurfaceLine = Color(0xFF334155)
    val Deep = Color(0xFF0F172A)
    val Heart = Color(0xFFF43F5E)
    val StopGray = Color(0xFF64748B)
}

// Material 3 tones around the app's emerald, like Google Maps keeps its own colours.
private val BrandColors = ColorScheme(
    primary = Color(0xFF63DBA6),
    primaryDim = Color(0xFF4CC390),
    primaryContainer = Color(0xFF005236),
    onPrimary = Color(0xFF003824),
    onPrimaryContainer = Color(0xFF82F8C1),
    secondary = Color(0xFFB4CCBE),
    secondaryDim = Color(0xFF9DB4A7),
    secondaryContainer = Color(0xFF354B40),
    onSecondary = Color(0xFF20352B),
    onSecondaryContainer = Color(0xFFD0E8DA),
    tertiary = Color(0xFFA5CCDF),
    tertiaryDim = Color(0xFF8FB5C7),
    tertiaryContainer = Color(0xFF244C5B),
    onTertiary = Color(0xFF073543),
    onTertiaryContainer = Color(0xFFC1E8FB),
    surfaceContainerLow = Color(0xFF151D19),
    surfaceContainer = Color(0xFF1B231F),
    surfaceContainerHigh = Color(0xFF252D29),
    onSurface = Color(0xFFDEE4DF),
    onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF3F4943),
    background = Color.Black,
    onBackground = Color(0xFFDEE4DF),
)

fun routeColor(hex: String): Color =
    runCatching { Color(hex.toColorInt()) }.getOrDefault(Palette.StopGray)

@Composable
fun ConexiuniClujWearOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandColors, content = content)
}
