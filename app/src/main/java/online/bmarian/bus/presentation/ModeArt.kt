package online.bmarian.bus.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.bmarian.bus.data.StationLook
import online.bmarian.bus.logic.Mode
import online.bmarian.bus.presentation.theme.routeColor

/** Port of utils/modeIcons.ts. */
object ModeGlyphs {
    const val BUS =
        "M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm9 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm-10-7V6h11v4H6.5z"
    const val TRAM =
        "M19 16.94V8.5c0-2.79-2.61-3.4-6.01-3.49l.76-1.51H17V2H7v1.5h4.75l-.76 1.52C7.86 5.11 5 5.73 5 8.5v8.44c0 1.45 1.19 2.66 2.59 2.97L6 21.5v.5h2.23l2-2H14l2 2h2v-.5L16.5 20h-.08c1.69 0 2.58-1.37 2.58-3.06zm-7 1.56c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm5-4.5H7V9h10v5z"
    const val TRAIN =
        "M12 2c-4 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h2.23l2-2H14l2 2h2v-.5L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6c0-3.5-3.58-4-8-4zM7.5 17c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm3.5-7H6V6h5v4zm2 0V6h5v4h-5zm3.5 7c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"
    const val METRO = "M3.5 20.5v-17h3.6L12 11l4.9-7.5h3.6v17h-3.3V9.4L12 17.2 6.8 9.4v11.1z"
    const val POLES = "M10.5 6.4 8.5 1.5M14 6.4 12 1.5"
    const val HEART =
        "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
    private val paths = HashMap<String, Path>()
    fun path(d: String): Path = synchronized(paths) { paths.getOrPut(d) { parse(d) } }

    fun of(mode: Mode): String = when (mode) {
        Mode.METRO -> METRO
        Mode.RAIL -> TRAIN
        Mode.TRAM -> TRAM
        Mode.TROLLEYBUS, Mode.BUS -> BUS
    }

    private val vectors = HashMap<Mode, ImageVector>()

    fun vector(mode: Mode): ImageVector = synchronized(vectors) {
        vectors.getOrPut(mode) {
            ImageVector.Builder(mode.name, 24.dp, 24.dp, 24f, 24f).apply {
                if (mode == Mode.TROLLEYBUS) {
                    group(scaleX = 0.8f, scaleY = 0.8f, translationX = 2.4f, translationY = 4.4f) {
                        addPath(addPathNodes(BUS), fill = SolidColor(Color.Black))
                    }
                    addPath(addPathNodes(POLES), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round)
                } else {
                    addPath(addPathNodes(of(mode)), fill = SolidColor(Color.Black))
                }
            }.build()
        }
    }
}

fun DrawScope.drawModeGlyph(mode: Mode, color: Color) {
    if (mode == Mode.TROLLEYBUS) {
        translate(2.4f, 4.4f) { scale(0.8f, 0.8f, pivot = Offset.Zero) { drawPath(ModeGlyphs.path(ModeGlyphs.BUS), color) } }
        drawPath(ModeGlyphs.path(ModeGlyphs.POLES), color, style = Stroke(1.6f, cap = StrokeCap.Round))
    } else {
        drawPath(ModeGlyphs.path(ModeGlyphs.of(mode)), color)
    }
}

@Composable
fun StationIcon(look: StationLook?, modifier: Modifier = Modifier, size: Dp = 36.dp, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
    val container = look?.let { routeColor(it.color) } ?: MaterialTheme.colorScheme.secondaryContainer
    val tint = if (look != null) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(ModeGlyphs.vector(look?.mode ?: Mode.BUS), null, Modifier.size(size * 0.5f), tint = tint)
    }
}

@Composable
fun StationLines(look: StationLook?, modifier: Modifier = Modifier) {
    val routes = look?.routes.orEmpty()
    if (routes.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (route in routes) {
            Box(
                Modifier.widthIn(min = 28.dp).clip(RoundedCornerShape(5.dp)).background(routeColor(route.color)).padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(route.shortName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
        }
    }
}
