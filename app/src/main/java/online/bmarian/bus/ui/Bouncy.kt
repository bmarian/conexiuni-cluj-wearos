package online.bmarian.bus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
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
    val fastSize = spring<IntSize>(dampingRatio = 0.6f, stiffness = 1980f)
    val press = spring<Float>(dampingRatio = 0.32f, stiffness = 860f)
    val pop = spring<Float>(dampingRatio = 0.26f, stiffness = 1980f)
    val slide = spring<Float>(dampingRatio = 0.58f, stiffness = 450f)
    val land = spring<Float>(dampingRatio = 0.55f, stiffness = 530f)
}

val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

class Pop {
    val x = Animatable(1f)
    val y = Animatable(1f)

    suspend fun from(scaleX: Float, scaleY: Float = scaleX) = coroutineScope {
        x.snapTo(scaleX)
        y.snapTo(scaleY)
        launch { x.animateTo(1f, Bouncy.pop) }
        y.animateTo(1f, Bouncy.pop)
    }

    suspend fun squash() = from(1.2f, 0.8f)
}

@Composable
fun rememberPop() = remember { Pop() }

fun Modifier.popped(pop: Pop): Modifier = graphicsLayer {
    scaleX = pop.x.value
    scaleY = pop.y.value
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
        delay(min(index, 8) * 35L)
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
                launch { burst.animateTo(1f, tween(450)) }
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
            .drawBehind { drawSparks(burst.value, tint) }
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -55f
            },
    )
}

/** PopIcon's ring of sparks at [burst] (0 to 1) around a [box]-sized icon. */
fun DrawScope.drawSparks(burst: Float, tint: Color, box: Float = size.minDimension) {
    if (burst >= 1f) return
    val reach = box * (0.55f + 0.75f * burst)
    val dot = box * 0.12f * (1f - burst)
    for (i in 0 until 8) {
        val angle = (i / 8f) * 2f * PI.toFloat() - PI.toFloat() / 2f
        drawCircle(tint.copy(alpha = (1f - burst) * tint.alpha), dot, center + Offset(cos(angle) * reach, sin(angle) * reach))
    }
}

private val DIGITS = Regex("""\d+""")

private fun numbers(text: String) = DIGITS.findAll(text).map { it.value.toLong() }.toList()

private fun shrinks(was: String, now: String): Boolean {
    val before = numbers(was)
    val after = numbers(now)
    val i = after.indices.firstOrNull { after[it] != before.getOrNull(it) } ?: return false
    return before.getOrNull(i)?.let { after[i] < it } == true
}

private fun <S> AnimatedContentTransitionScope<S>.roll(): ContentTransform =
    (slideInVertically(spring(dampingRatio = 0.5f, stiffness = 530f)) { it } + fadeIn()) togetherWith
        (slideOutVertically(spring(stiffness = 530f)) { -it } + fadeOut()) using SizeTransform(clip = false)

@Composable
fun <T> Rolling(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(value, modifier, transitionSpec = { roll() }, label = "rolling") { content(it) }
}

// Port of Ticker.
@Composable
fun Ticker(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    var last by remember { mutableStateOf(text) }
    val down = remember(text) { shrinks(last, text) }
    SideEffect { last = text }
    AnimatedContent(
        text,
        modifier,
        contentKey = { it.replace(DIGITS, "#") },
        transitionSpec = { roll() },
        label = "ticker",
    ) { value ->
        if (!DIGITS.containsMatchIn(value)) {
            Text(value, style = style, color = color, maxLines = 1)
            return@AnimatedContent
        }
        Row(Modifier.clearAndSetSemantics { this.text = AnnotatedString(value) }) {
            var at = 0
            for (run in DIGITS.findAll(value)) {
                if (run.range.first > at) Text(value.substring(at, run.range.first), style = style, color = color, maxLines = 1)
                Row(Modifier.animateContentSize(Bouncy.fastSize)) {
                    run.value.forEachIndexed { k, digit ->
                        key(run.value.length - k) {
                            AnimatedContent(
                                digit,
                                transitionSpec = {
                                    val from = if (down) -1 else 1
                                    (slideInVertically(spring(dampingRatio = 0.5f, stiffness = 530f)) { it * from } + fadeIn()) togetherWith
                                        (slideOutVertically(spring(stiffness = 530f)) { -it * from } + fadeOut()) using SizeTransform(clip = true)
                                },
                                label = "digit",
                            ) { d ->
                                Text(d.toString(), style = style.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1)
                            }
                        }
                    }
                }
                at = run.range.last + 1
            }
            if (at < value.length) Text(value.substring(at), style = style, color = color, maxLines = 1)
        }
    }
}
