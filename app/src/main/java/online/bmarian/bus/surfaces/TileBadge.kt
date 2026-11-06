package online.bmarian.bus.surfaces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.graphics.createBitmap
import online.bmarian.bus.ui.theme.lineShadow
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

class TileBadge(val png: ByteArray, val widthPx: Int, val heightPx: Int, val widthDp: Float, val heightDp: Float)

private const val HEIGHT_DP = 18f
private const val PAD_DP = 5f
private const val CORNER_DP = 6f
private const val TEXT_SP = 12f

// Tile text has no shadow.
fun tileBadge(context: Context, line: String, fill: Int): TileBadge {
    val metrics = context.resources.displayMetrics
    val d = metrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, metrics)
        lineShadow(d)
    }
    val heightPx = ceil(HEIGHT_DP * d).toInt()
    val widthPx = ceil(text.measureText(line) + 2 * PAD_DP * d).toInt().coerceAtLeast(heightPx)
    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)
    val corner = CORNER_DP * d
    canvas.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    canvas.drawText(line, widthPx / 2f, heightPx / 2f - (text.descent() + text.ascent()) / 2f, text)
    val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    bitmap.recycle()
    return TileBadge(png, widthPx, heightPx, widthPx / d, HEIGHT_DP)
}
