package online.bmarian.bus.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
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
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import online.bmarian.bus.R
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.presentation.theme.routeColor
import java.time.LocalDateTime

/** A list screen, optionally with the screen's main action hugging the bottom edge. */
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

/** Size and scroll effect for a Material component in a [ListScreen]; pass it [surface] too. */
fun TransformingLazyColumnItemScope.itemModifier(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth().transformedHeight(this, spec)

/** The same for anything else, which has to be scaled along with the space it gets. */
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

/** A line number in its CTP colour. */
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

/** The arcs Google Maps puts next to a time that comes from a tracked vehicle. */
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

@Composable
fun etaText(minutes: Int, now: LocalDateTime): String {
    val nowLabel = stringResource(R.string.now)
    return if (minutes in 1..59) stringResource(R.string.eta_minutes, minutes)
    else formatMinutesFromNow(minutes, now, nowLabel)
}

/** Live times in the accent colour with the realtime arcs; timetable times plain. */
@Composable
fun Eta(minutes: Int, live: Boolean, now: LocalDateTime, style: TextStyle = MaterialTheme.typography.labelLarge, arcs: Boolean = true) {
    val color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (live && arcs) RealtimeIcon(color, Modifier.padding(bottom = 2.dp))
        Text(etaText(minutes, now), style = style, color = color, maxLines = 1)
    }
}
