package online.bmarian.bus.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import online.bmarian.bus.logic.LONG_PACE
import online.bmarian.bus.logic.paced
import online.bmarian.bus.ui.theme.Palette
import kotlin.math.hypot

private const val MORPH_MS = (1130 * LONG_PACE).toInt()

/** Port of the phone's FavoriteMorph.kt. */
@Stable
class Morph(color: Color, private var saved: Boolean?) {
    enum class Mark { Heart, Broken }

    var before by mutableStateOf(color)
        private set
    var now by mutableStateOf(color)
        private set
    var mark by mutableStateOf<Mark?>(null)
        private set
    val pop = Animatable(1f)
    val fill = Animatable(1f)
    val phase = Animatable(1f)
    val content = Animatable(1f)
    val heart = Animatable(0f)
    val spin = Animatable(0f)
    val burst = Animatable(1f)
    val crack = Animatable(0f)

    suspend fun to(color: Color, favorite: Boolean?) {
        val was = saved
        saved = favorite
        settle()
        when {
            was == null || favorite == null -> {
                before = color
                now = color
            }
            favorite != was -> swap(favorite, color)
            color != now -> recolor(color)
        }
    }

    private suspend fun settle() {
        mark = null
        before = now
        for (a in listOf(pop, fill, phase, content, burst)) a.snapTo(1f)
        for (a in listOf(heart, spin, crack)) a.snapTo(0f)
    }

    private suspend fun recolor(color: Color) = coroutineScope {
        now = color
        fill.snapTo(0f)
        pop.snapTo(0.8f)
        launch { fill.animateTo(1f, Bouncy.slide) }
        pop.animateTo(1f, Bouncy.pop)
    }

    private suspend fun swap(saving: Boolean, color: Color) {
        now = color
        mark = if (saving) Mark.Heart else Mark.Broken
        coroutineScope {
            launch {
                pop.snapTo(0.8f)
                pop.animateTo(1f, Bouncy.pop)
            }
            launch {
                phase.snapTo(0f)
                phase.animateTo(1f, tween(MORPH_MS, easing = LinearEasing))
            }
            launch {
                content.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
                delay(paced(820L))
                content.animateTo(1f, Bouncy.pop)
            }
            delay(paced(85L))
            heart.snapTo(0.25f)
            launch { heart.animateTo(1f, Bouncy.pop) }
            if (saving) {
                spin.snapTo(-41f)
                burst.snapTo(0f)
                launch { burst.animateTo(1f, tween(paced(450))) }
                launch { spin.animateTo(0f, Bouncy.pop) }
                delay(paced(740L))
                heart.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
            } else {
                delay(paced(350L))
                crack.animateTo(1f, tween(paced(565), easing = FastOutLinearInEasing))
            }
        }
        mark = null
    }

    fun background(markColor: Color): Color {
        val t = phase.value
        if (mark == null || t >= 1f) return now
        return when {
            t < 0.12f -> lerp(before, markColor, t / 0.12f)
            t < 0.75f -> markColor
            else -> lerp(markColor, now, (t - 0.75f) / 0.25f)
        }
    }
}

/** A new [subject] starts still. */
@Composable
fun rememberMorph(subject: Any?, color: Color, favorite: Boolean?): Morph {
    val morph = remember(subject) { Morph(color, favorite) }
    LaunchedEffect(morph, color, favorite) { morph.to(color, favorite) }
    return morph
}

@Composable
fun FavoriteMorph(morph: Morph, shape: Shape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val heart = rememberVectorPainter(Icons.Rounded.Favorite)
    val broken = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .graphicsLayer {
                scaleX = morph.pop.value
                scaleY = morph.pop.value
            }
            .drawWithContent {
                drawContent()
                when (morph.mark) {
                    Morph.Mark.Heart -> drawHeart(heart, morph)
                    Morph.Mark.Broken -> drawBrokenHeart(heart, morph)
                    null -> {}
                }
            }
            .clip(shape)
            .drawBehind {
                val fill = morph.fill.value
                if (fill >= 1f) return@drawBehind drawRect(morph.background(if (morph.mark == Morph.Mark.Broken) broken else Palette.Heart))
                drawRect(morph.before)
                drawCircle(morph.now, hypot(size.width, size.height) / 2 * fill.coerceAtLeast(0f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = morph.content.value
                scaleY = morph.content.value
            },
        ) { content() }
    }
}

private fun DrawScope.heartSide() = size.height * 0.55f

private fun DrawScope.drawHeart(heart: VectorPainter, morph: Morph) {
    val side = heartSide()
    drawSparks(morph.burst.value, Palette.Heart, side)
    val scale = morph.heart.value
    if (scale <= 0f) return
    withTransform({
        rotate(morph.spin.value, center)
        scale(scale, scale, center)
        translate(center.x - side / 2, center.y - side / 2)
    }) {
        with(heart) { draw(Size(side, side), colorFilter = ColorFilter.tint(Color.White)) }
    }
}

// The crack, as fractions of the heart's box; the right half is the rest.
private val CRACK = listOf(0.5f to 0f, 0.4f to 0.35f, 0.58f to 0.55f, 0.46f to 0.75f, 0.5f to 1f)

private fun DrawScope.drawBrokenHeart(heart: VectorPainter, morph: Morph) {
    val side = heartSide()
    val scale = morph.heart.value
    if (scale <= 0f) return
    val k = morph.crack.value
    val tip = if (k < 0.25f) k / 0.25f else 1f
    val fall = if (k < 0.25f) 0f else (k - 0.25f) / 0.75f
    val spread = 0.08f * tip + 0.08f * fall
    val tilt = 18f * tip + 16f * fall
    val alpha = 1f - fall
    for (dir in listOf(-1f, 1f)) {
        val half = Path().apply {
            val edge = if (dir < 0) 0f else side
            moveTo(edge, 0f)
            CRACK.forEach { (x, y) -> lineTo(x * side, y * side) }
            lineTo(edge, side)
            close()
        }
        withTransform({
            scale(scale, scale, center)
            translate(dir * spread * side, 0.9f * fall * side)
            rotate(dir * tilt, Offset(center.x, center.y + side / 2))
            translate(center.x - side / 2, center.y - side / 2)
        }) {
            clipPath(half) { with(heart) { draw(Size(side, side), alpha, ColorFilter.tint(Color.White)) } }
        }
    }
}
