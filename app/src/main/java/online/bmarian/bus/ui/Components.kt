package online.bmarian.bus.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import online.bmarian.bus.logic.Mode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.ButtonGroupScope
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.TextUnit
import online.bmarian.bus.R
import online.bmarian.bus.follow.minutesUntil
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.ui.theme.LocalLineLooks
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ListScreen(
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val spec = rememberTransformationSpec()
    val list: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = PaddingValues(
                start = padding.calculateLeftPadding(LayoutDirection.Ltr),
                end = padding.calculateRightPadding(LayoutDirection.Ltr),
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content(spec)
        }
    }
    if (edgeButton != null) {
        ScreenScaffold(scrollState = state, edgeButton = edgeButton, content = list)
    } else {
        ScreenScaffold(scrollState = state, content = list)
    }
}

fun TransformingLazyColumnItemScope.itemModifier(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth().transformedHeight(this, spec)

fun TransformingLazyColumnItemScope.contentModifier(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth()
        .transformedHeight(this, spec)
        .graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } }

fun TransformingLazyColumnItemScope.surface(spec: TransformationSpec) = SurfaceTransformation(spec)

@Composable
fun TransformingLazyColumnItemScope.Title(text: String, spec: TransformationSpec) {
    ListHeader(modifier = itemModifier(spec), transformation = surface(spec)) {
        Text(text, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TransformingLazyColumnItemScope.SubHeader(text: String, spec: TransformationSpec) {
    ListSubHeader(modifier = itemModifier(spec), transformation = surface(spec)) { Text(text) }
}

@Composable
fun TransformingLazyColumnItemScope.Message(text: String, spec: TransformationSpec) {
    Text(
        text,
        modifier = contentModifier(spec).padding(horizontal = 8.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun TransformingLazyColumnItemScope.Loading(spec: TransformationSpec) {
    Box(contentModifier(spec).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp))
    }
}

@Composable
fun TransformingLazyColumnItemScope.LineHeader(spec: TransformationSpec, shortName: String?, color: String?, text: String) {
    ListHeader(modifier = itemModifier(spec), transformation = surface(spec)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shortName != null && color != null) LineBadge(shortName, color)
            if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Start, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 2,
    minFontSize: TextUnit = 10.sp,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text,
        modifier,
        style = style.copy(color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }, textAlign = textAlign ?: style.textAlign),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = style.fontSize),
    )
}

@Composable
fun LineBadge(shortName: String, color: String, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    val looks = LocalLineLooks.current
    val morph = rememberMorph(shortName, looks.color(shortName, color), looks.favorite(shortName))
    FavoriteMorph(morph, RoundedCornerShape(height / 3), modifier.widthIn(min = height * 1.3f).heightIn(min = height)) {
        Text(
            shortName,
            Modifier.padding(horizontal = 5.dp),
            color = Color.White,
            fontSize = (height.value * 0.5f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** When fresh live times last landed. */
val LocalLiveBeat = compositionLocalOf<Long?> { null }

@Composable
fun OnLiveBeat(action: suspend CoroutineScope.() -> Unit) {
    val beat = LocalLiveBeat.current
    var seen by remember { mutableStateOf(beat) }
    LaunchedEffect(beat) {
        if (beat == null || beat == seen) return@LaunchedEffect
        seen = beat
        action()
    }
}

// Port of RealtimeIcon.
@Composable
fun RealtimeIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val parts = remember { List(3) { Animatable(1f) } }
    OnLiveBeat {
        parts.forEach { it.snapTo(0.2f) }
        parts.forEachIndexed { i, part ->
            launch {
                delay(120L + i * 95L)
                part.animateTo(1f, tween(210, easing = LinearEasing))
            }
        }
    }
    Canvas(modifier.size(size)) {
        val stroke = this.size.width * 0.13f
        val corner = Offset(stroke, this.size.height - stroke)
        drawCircle(color.copy(alpha = color.alpha * parts[0].value), stroke * 1.1f, corner)
        for (step in 1..2) {
            val radius = this.size.width * (0.32f + 0.3f * (step - 1))
            drawArc(
                color = color.copy(alpha = color.alpha * parts[step].value),
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(corner.x - radius, corner.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun Countdown(atMs: Long, style: TextStyle, color: Color = Color.Unspecified) {
    rememberClock().value
    val minutes = minutesUntil(atMs, System.currentTimeMillis())
    Ticker(etaText(minutes, LocalDateTime.now(CLUJ_ZONE)), style = style, color = color)
}

@Composable
fun etaText(minutes: Int, now: LocalDateTime): String {
    val nowLabel = stringResource(R.string.now)
    return if (minutes in 1..59) stringResource(R.string.eta_minutes, minutes)
    else formatMinutesFromNow(minutes, now, nowLabel)
}

fun clockText(atMs: Long): String = CLOCK.format(Instant.ofEpochMilli(atMs).atZone(CLUJ_ZONE))

@Composable
fun ModeIcon(mode: Mode, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { scale(size.width / 24f, pivot = Offset.Zero) { drawModeGlyph(mode, color) } }
}

// Port of TimePill.
@Composable
fun Eta(
    minutes: Int,
    live: Boolean,
    now: LocalDateTime,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    arcs: Boolean = true,
    mode: Mode = Mode.BUS,
    iconSize: Dp = 12.dp,
    idle: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val scheme = MaterialTheme.colorScheme
    val due = live && minutes == 0
    val solid = tween<Color>(190, delayMillis = 485, easing = LinearEasing)
    val fill by animateColorAsState(if (due) scheme.primary else scheme.primary.copy(alpha = 0f), if (due) solid else snap(), label = "eta")
    val color by animateColorAsState(
        when {
            due -> scheme.onPrimary
            live -> scheme.primary
            else -> idle
        },
        if (due) solid else snap(),
        label = "etaText",
    )
    val pill = rememberPop()
    val vehicle = rememberPop()
    val arrive = remember { Animatable(1f) }
    var was by remember { mutableStateOf(live to due) }
    // Not when a screen opens on a bus already due.
    LaunchedEffect(due) {
        val (wasLive, wasDue) = was
        was = live to due
        if (!due || wasDue || !wasLive) return@LaunchedEffect arrive.snapTo(1f)
        arrive.snapTo(0f)
        launch {
            delay(70)
            arrive.animateTo(1f, tween(540, easing = EmphasizedDecelerate))
        }
        launch {
            delay(540)
            pill.from(0.86f)
        }
        delay(610)
        vehicle.squash()
    }
    Row(
        Modifier.popped(pill).clip(CircleShape).background(fill).padding(horizontal = iconSize / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(iconSize / 4),
    ) {
        if (live && arcs) {
            Box(Modifier.padding(bottom = 2.dp).size(iconSize), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(!due, enter = fadeIn(), exit = scaleOut(tween(140)) + fadeOut(tween(140))) {
                    RealtimeIcon(color, size = iconSize)
                }
                if (due) {
                    ModeIcon(
                        mode,
                        color,
                        Modifier.fillMaxSize().popped(vehicle).graphicsLayer {
                            val k = 0.15f + 0.85f * arrive.value
                            scaleX *= k
                            scaleY *= k
                            alpha = arrive.value.coerceIn(0f, 1f)
                        },
                    )
                }
            }
        }
        Ticker(etaText(minutes, now), style = style, color = color)
    }
}

@Composable
fun Eta(atMs: Long, live: Boolean, style: TextStyle = MaterialTheme.typography.labelLarge) {
    rememberClock().value
    Eta(minutesUntil(atMs, System.currentTimeMillis()), live, LocalDateTime.now(CLUJ_ZONE), style)
}

private val ACTION_HEIGHT = 48.dp
// A labelled action beside icon-only ones.
const val LABELLED_WEIGHT = 1.7f

/** As wide as the list's other rows: the group's own edge padding would double the list's. */
@Composable
fun TransformingLazyColumnItemScope.ActionRow(spec: TransformationSpec, content: @Composable ButtonGroupScope.() -> Unit) {
    ButtonGroup(contentModifier(spec), spacing = 6.dp, contentPadding = PaddingValues(0.dp), content = content)
}

@Composable
fun ButtonGroupScope.PillAction(icon: ImageVector, description: String, onClick: () -> Unit, weight: Float = 1f) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.weight(weight).animateWidth(interaction).height(ACTION_HEIGHT),
        shapes = IconButtonDefaults.animatedShapes(),
        interactionSource = interaction,
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
fun ButtonGroupScope.LabelAction(icon: ImageVector, label: String, onClick: () -> Unit, weight: Float = LABELLED_WEIGHT) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = Modifier.weight(weight).animateWidth(interaction).height(ACTION_HEIGHT).squish(interaction, 0.9f),
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        interactionSource = interaction,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            FitText(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun ButtonGroupScope.ToggleAction(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.primary,
    onColor: Color = MaterialTheme.colorScheme.onPrimary,
    weight: Float = 1f,
    iconOnly: Boolean = false,
    checkedLabel: String = label,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(
        if (checked) 14.dp else ACTION_HEIGHT / 2,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 530f),
        label = "corner",
    )
    Button(
        onClick = {
            haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onClick()
        },
        modifier = Modifier.weight(weight).animateWidth(interaction).height(ACTION_HEIGHT).squish(interaction, 0.9f).semantics { selected = checked },
        shape = RoundedCornerShape(corner),
        colors = if (checked) ButtonDefaults.buttonColors(containerColor = color, contentColor = onColor, iconColor = onColor)
        else ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        interactionSource = interaction,
    ) {
        val shown = if (checked) checkedLabel else label
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PopIcon(icon, checked, Modifier.size(18.dp), contentDescription = shown.takeIf { iconOnly })
            if (!iconOnly) FitText(shown, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
