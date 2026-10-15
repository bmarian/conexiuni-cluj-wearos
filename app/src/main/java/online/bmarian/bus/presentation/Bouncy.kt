package online.bmarian.bus.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Bouncy {
    val press = spring<Float>(dampingRatio = 0.32f, stiffness = 650f)
    val pop = spring<Float>(dampingRatio = 0.26f, stiffness = Spring.StiffnessMedium)
    val land = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
}

/** Squashes while [source] is pressed and springs back on release. */
fun Modifier.squish(source: InteractionSource, pressed: Float = 0.92f): Modifier = composed {
    val down by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) pressed else 1f, Bouncy.press, label = "squish")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Rises into place after [index] × a beat, once per [key], so lists arrive as a cascade. */
fun Modifier.cascade(index: Int, key: Any?): Modifier = composed {
    val rise = with(LocalDensity.current) { 22.dp.toPx() }
    val tag = key.toString()
    // Saved with the list item, so scrolling back doesn't replay it.
    var played by rememberSaveable { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(if (played == tag) 1f else 0f) }
    LaunchedEffect(tag) {
        if (played == tag) return@LaunchedEffect
        played = tag
        progress.snapTo(0f)
        delay(min(index, 8) * 40L)
        progress.animateTo(1f, Bouncy.land)
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise
        scaleX = 0.9f + 0.1f * p
        scaleY = 0.9f + 0.1f * p
    }
}

/** An icon that pops, tilts and throws a ring of sparks when [on] turns true. */
@Composable
fun PopIcon(icon: ImageVector, on: Boolean, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val tint = LocalContentColor.current
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on == shown) return@LaunchedEffect
        shown = on
        if (on) {
            burst.snapTo(0f)
            pop.snapTo(0.25f)
            coroutineScope {
                launch { burst.animateTo(1f, tween(520)) }
                pop.animateTo(1f, Bouncy.pop)
            }
        } else {
            pop.snapTo(0.8f)
            pop.animateTo(1f, Bouncy.pop)
        }
    }
    Icon(
        icon,
        contentDescription,
        modifier
            .drawBehind {
                val b = burst.value
                if (b >= 1f) return@drawBehind
                val reach = size.minDimension * (0.55f + 0.75f * b)
                val dot = size.minDimension * 0.12f * (1f - b)
                for (i in 0 until 8) {
                    val angle = (i / 8f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                    drawCircle(tint.copy(alpha = (1f - b) * tint.alpha), dot, center + Offset(cos(angle) * reach, sin(angle) * reach))
                }
            }
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -55f
            },
    )
}

/** Text that rolls up to its new value. */
@Composable
fun Ticker(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    AnimatedContent(
        text,
        modifier,
        transitionSpec = {
            (slideInVertically(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn()) togetherWith
                (slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeOut()) using SizeTransform(clip = false)
        },
        label = "ticker",
    ) { value ->
        Text(value, style = style, color = color, maxLines = 1)
    }
}
