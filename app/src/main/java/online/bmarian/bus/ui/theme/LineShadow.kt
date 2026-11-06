package online.bmarian.bus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity

private val LineShadow = Color.Black.copy(alpha = 0.8f)

fun lineNumberShadow(density: Float) = Shadow(LineShadow, Offset(0f, density), blurRadius = 3f * density)

@Composable
fun lineNumberShadow(): Shadow = lineNumberShadow(LocalDensity.current.density)

fun android.graphics.Paint.lineShadow(density: Float) = apply { setShadowLayer(1.5f * density, 0f, density, LineShadow.toArgb()) }
