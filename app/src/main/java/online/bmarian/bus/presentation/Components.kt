package online.bmarian.bus.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import online.bmarian.bus.R
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.presentation.theme.routeColor
import java.time.LocalDateTime

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
                Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LineBadge(shortName: String, color: String, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Box(
        modifier = modifier
            .widthIn(min = height * 1.3f)
            .heightIn(min = height)
            .clip(RoundedCornerShape(height / 3))
            .background(routeColor(color))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shortName,
            color = Color.White,
            fontSize = (height.value * 0.5f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun RealtimeIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.width * 0.13f
        val corner = Offset(stroke, this.size.height - stroke)
        drawCircle(color, stroke * 1.1f, corner)
        for (step in 1..2) {
            val radius = this.size.width * (0.32f + 0.3f * (step - 1))
            drawArc(
                color = color,
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

// Rounded: the phone sends whole minutes and the clocks drift a few seconds.
fun minutesUntil(atMs: Long, nowMs: Long): Int = ((atMs - nowMs + 30_000) / 60_000).toInt().coerceAtLeast(0)

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

@Composable
fun Eta(minutes: Int, live: Boolean, now: LocalDateTime, style: TextStyle = MaterialTheme.typography.labelLarge, arcs: Boolean = true) {
    val color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (live && arcs) RealtimeIcon(color, Modifier.padding(bottom = 2.dp))
        Ticker(etaText(minutes, now), style = style, color = color)
    }
}

private val ACTION_HEIGHT = 48.dp
// A labelled action beside icon-only ones.
const val LABELLED_WEIGHT = 1.7f

@Composable
fun TransformingLazyColumnItemScope.ActionRow(spec: TransformationSpec, content: @Composable ButtonGroupScope.() -> Unit) {
    ButtonGroup(contentModifier(spec), spacing = 6.dp, content = content)
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
fun ButtonGroupScope.ToggleAction(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.primary,
    onColor: Color = MaterialTheme.colorScheme.onPrimary,
    weight: Float = 1f,
    iconOnly: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(
        if (checked) 14.dp else ACTION_HEIGHT / 2,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PopIcon(icon, checked, Modifier.size(18.dp), contentDescription = label.takeIf { iconOnly })
            if (!iconOnly) {
                BasicText(
                    label,
                    Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                )
            }
        }
    }
}
