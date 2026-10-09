package online.bmarian.bus.presentation.map

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.bmarian.bus.presentation.Glyphs
import online.bmarian.bus.presentation.theme.Palette
import kotlin.math.ceil
import kotlin.math.roundToInt

private val BusStop by lazy { PathParser().parsePathString(Glyphs.BUS_STOP_PATH).toPath() }
private val NavArrow by lazy { PathParser().parsePathString("M12 2L21 21l-9-4-9 4 9-19z").toPath() }

internal class MapImages(context: Context) {
    private val density = Density(context)
    private val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)

    private fun draw(widthDp: Float, heightDp: Float, block: DrawScope.() -> Unit): Bitmap {
        val w = ceil(widthDp * density.density).toInt().coerceAtLeast(1)
        val h = ceil(heightDp * density.density).toInt().coerceAtLeast(1)
        val image = ImageBitmap(w, h)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat()), block)
        return image.asAndroidBitmap().apply { this.density = (this@MapImages.density.density * DisplayMetrics.DENSITY_DEFAULT).roundToInt() }
    }

    fun stop(color: Color, selected: Boolean): Bitmap {
        val d = if (selected) STOP_SELECTED_DP else STOP_DP
        return draw(d + 2f, d + 2f) {
            val r = d.dp.toPx() / 2
            drawCircle(Color.Black.copy(alpha = 0.25f), r + 1.dp.toPx(), center)
            drawCircle(Color.White, r, center)
            drawCircle(color, r - 1.5.dp.toPx(), center)
            val glyph = r * 1.15f
            translate(center.x - glyph / 2, center.y - glyph / 2) {
                scale(glyph / 24f, glyph / 24f, pivot = Offset.Zero) { drawPath(BusStop, Color.White) }
            }
        }
    }

    fun vehicle(color: Color, label: String) = draw(BUS_DP + 2f, BUS_DP + 2f) {
        val r = BUS_DP.dp.toPx() / 2
        drawCircle(Color.Black.copy(alpha = 0.25f), r + 1.dp.toPx(), center)
        drawCircle(Color.White, r, center)
        drawCircle(color, r - 1.5.dp.toPx(), center)
        val size = when {
            label.length >= 4 -> 7.sp
            label.length >= 3 -> 8.sp
            else -> 9.sp
        }
        val layout = measurer.measure(label, TextStyle(fontSize = size, fontWeight = FontWeight.Black, color = Color.White))
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
    }

    fun heading() = draw(11f, 11f) {
        val r = 4.5.dp.toPx()
        drawCircle(Color.White, r + 0.5.dp.toPx(), center)
        drawCircle(Color(0xD90F172A), r, center)
        val arrow = r * 1.25f
        translate(center.x - arrow / 2, center.y - arrow / 2) {
            scale(arrow / 24f, arrow / 24f, pivot = Offset.Zero) { drawPath(NavArrow, Color.White) }
        }
    }

    fun label(text: String, live: Boolean): Bitmap {
        val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (live) Palette.Accent else Color(0xFFE2E8F0))
        val layout = measurer.measure(text, style)
        val d = density.density
        val w = layout.size.width / d + 8f
        val h = layout.size.height / d + 2f
        return draw(w + 1f, h + 1f) {
            val corner = CornerRadius(4.dp.toPx())
            val box = Size(w.dp.toPx(), h.dp.toPx())
            drawRoundRect(Color(0xEB0F172A), Offset(0.5f, 0.5f), box, corner)
            drawRoundRect(if (live) Palette.AccentDeep else Palette.SurfaceLine, Offset(0.5f, 0.5f), box, corner, style = Stroke(1.dp.toPx()))
            drawText(layout, topLeft = Offset(4.dp.toPx() + 0.5f, 1.dp.toPx() + 0.5f))
        }
    }

    companion object {
        const val STOP_DP = 15f
        const val STOP_SELECTED_DP = 19f
        const val BUS_DP = 22f
    }
}
