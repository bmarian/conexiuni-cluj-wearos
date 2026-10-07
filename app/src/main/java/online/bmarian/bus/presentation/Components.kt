package online.bmarian.bus.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.routeColor

/** A scrolling screen whose rows shrink and fade towards the rim. The bezel scrolls it. */
@Composable
fun ListScreen(
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    bottomInset: Dp = 0.dp,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = PaddingValues(
                start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content(spec)
        }
    }
}

fun TransformingLazyColumnItemScope.transformed(spec: TransformationSpec): Modifier =
    Modifier
        .transformedHeight(this, spec)
        .graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } }

@Composable
fun Heading(title: String, modifier: Modifier = Modifier, kicker: String? = null, badge: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (kicker != null) Text(kicker, color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        badge?.invoke()
        if (title.isNotEmpty()) {
            Text(
                title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        color = Palette.Label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        color = Palette.Sub,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    busy: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (busy) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = Palette.Title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sub.isNullOrEmpty()) {
                Text(sub, color = Palette.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun ActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Palette.Action,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Text(text, color = Palette.Action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun RouteBadge(shortName: String, color: String, small: Boolean = false) {
    Box(
        modifier = Modifier
            .widthIn(min = if (small) 30.dp else 42.dp)
            .heightIn(min = if (small) 22.dp else 32.dp)
            .clip(RoundedCornerShape(if (small) 7.dp else 10.dp))
            .background(routeColor(color))
            .padding(horizontal = if (small) 5.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shortName,
            color = Color.White,
            fontSize = if (small) 12.sp else 15.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

@Composable
fun RoundIcon(icon: ImageVector, tint: Color, background: Color) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun StopIcon() = RoundIcon(Icons.Pin, Palette.Accent, Palette.AccentWash)

@Composable
fun BrowseIcon(icon: ImageVector) = RoundIcon(icon, Palette.Sub, Palette.Surface)

@Composable
fun FavoriteToggle(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    addLabel: String,
    removeLabel: String,
    modifier: Modifier = Modifier,
) {
    ActionRow(
        text = if (isFavorite) removeLabel else addLabel,
        onClick = onToggle,
        modifier = modifier,
        icon = if (isFavorite) Icons.HeartFilled else Icons.HeartOutline,
        iconTint = if (isFavorite) Palette.Heart else Palette.Action,
    )
}

@Composable
fun FloatButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(Palette.FloatFill)
            .border(1.dp, Palette.FloatLine, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.FloatText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FloatIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Palette.Surface)
            .border(1.dp, Palette.SurfaceLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Palette.Action, modifier = Modifier.size(20.dp))
    }
}
