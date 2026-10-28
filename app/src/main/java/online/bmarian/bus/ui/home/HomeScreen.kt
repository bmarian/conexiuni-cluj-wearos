package online.bmarian.bus.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Route
import online.bmarian.bus.data.bothWays
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.FollowState
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.Reminder
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.follow.minutesUntil
import online.bmarian.bus.journey.JourneyMirror
import online.bmarian.bus.journey.JourneySync
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.Mode
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.formatMeters
import online.bmarian.bus.ui.ActionRow
import online.bmarian.bus.ui.Countdown
import online.bmarian.bus.ui.Eta
import online.bmarian.bus.ui.FitText
import online.bmarian.bus.ui.LabelAction
import online.bmarian.bus.ui.LineBadge
import online.bmarian.bus.ui.ListScreen
import online.bmarian.bus.ui.Loading
import online.bmarian.bus.ui.LocalLiveBeat
import online.bmarian.bus.ui.Message
import online.bmarian.bus.ui.PillAction
import online.bmarian.bus.ui.SubHeader
import online.bmarian.bus.ui.cascade
import online.bmarian.bus.ui.clockText
import online.bmarian.bus.ui.contentModifier
import online.bmarian.bus.ui.follow.followStatus
import online.bmarian.bus.ui.itemModifier
import online.bmarian.bus.ui.rememberClock
import online.bmarian.bus.ui.squish
import online.bmarian.bus.ui.stop.destination
import online.bmarian.bus.ui.surface
import java.time.LocalDateTime

private val ROW_MIN_HEIGHT = 36.dp
private val CARD_SHAPE = RoundedCornerShape(26.dp)

private class SavedLine(val route: Route, val direction: String, val label: String)

@Composable
fun HomeScreen(
    state: TransformingLazyColumnState,
    onOpenStop: (Int) -> Unit,
    onOpenDeparture: (Int, StopDeparture) -> Unit,
    onOpenRoute: (Int, String) -> Unit,
    onOpenFollow: (Followed) -> Unit,
    onOpenJourney: () -> Unit,
    onSearch: () -> Unit,
    onMap: () -> Unit,
    onSettings: () -> Unit,
) {
    val repository = Services.repository
    val vm: HomeViewModel = viewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
    LaunchedEffect(Unit) {
        repository.loadCached()
        repository.refreshNetwork()
    }
    val here = rememberHere()
    LaunchedEffect(here.fix) { vm.locate(here.fix) }

    val ui by vm.ui.collectAsStateWithLifecycle()
    val beat by vm.beat.collectAsStateWithLifecycle()
    val routes by repository.routes.collectAsStateWithLifecycle()
    val favoriteStops by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val journey by JourneyMirror.state.collectAsStateWithLifecycle()

    val savedLines = remember(routes, favoriteRoutes) {
        val byId = routes.associateBy { it.routeId }
        // Both directions saved: one row that opens the direction nearest the user.
        favoriteRoutes.groupBy { it.routeId }.mapNotNull { (routeId, favorites) ->
            val route = byId[routeId] ?: return@mapNotNull null
            if (favorites.size > 1) SavedLine(route, "auto", bothWays(route.longName))
            else SavedLine(route, favorites[0].direction, "→ " + terminalName(route.longName, favorites[0].direction))
        }
    }

    CompositionLocalProvider(LocalLiveBeat provides beat) {
        ListScreen(state = state) { spec ->
            journey?.let { trip -> item(key = "journey") { JourneyCard(trip, spec, onOpenJourney) } }
            items(follows.size, key = { "follow-${follows[it].id}" }) { i ->
                FollowCard(follows[i], spec) { onOpenFollow(follows[i].followed) }
            }
            items(reminders.size, key = { "reminder-${reminders[it].id}" }) { i ->
                ReminderCard(reminders[i], spec) { onOpenFollow(reminders[i].followed) }
            }

            val current = ui
            val nearby = current?.nearby
            item(key = "near-label") {
                val distance = nearby?.meters?.let { " · " + formatMeters(it) }.orEmpty()
                SubHeader(stringResource(R.string.near_you) + distance, spec)
            }
            when {
                here.status == HereStatus.NotAllowed || here.status == HereStatus.Off -> item(key = "near-enable") {
                    val press = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = here.ask,
                        modifier = itemModifier(spec).squish(press),
                        interactionSource = press,
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
                        label = { Text(stringResource(R.string.nearby_enable), maxLines = 2) },
                        secondaryLabel = if (here.status == HereStatus.Off) ({ Text(stringResource(R.string.nearby_unavailable)) }) else null,
                    )
                }
                here.status == HereStatus.Waiting || current == null -> item(key = "near-loading") { Loading(spec) }
                nearby == null -> item(key = "near-none") { Message(stringResource(R.string.nearby_none), spec) }
                else -> item(key = "near-${nearby.stopId}") { StationCard(nearby, current.now, 0, spec, onOpenStop, onOpenDeparture) }
            }

            val saved = current?.saved.orEmpty()
            val noFavorites = favoriteStops.isEmpty() && savedLines.isEmpty()
            val savedLoading = current == null && favoriteStops.isNotEmpty()
            // The nearest stop may be the only saved one.
            if (noFavorites || savedLoading || saved.isNotEmpty() || savedLines.isNotEmpty()) {
                item(key = "saved-label") { SubHeader(stringResource(R.string.saved_stops), spec) }
            }
            if (savedLoading) item(key = "saved-loading") { Loading(spec) }
            else if (noFavorites) item(key = "saved-none") { Message(stringResource(R.string.no_favorites), spec) }
            if (current != null) {
                items(saved.size, key = { "saved-${saved[it].stopId}" }) { i -> StationCard(saved[i], current.now, i, spec, onOpenStop, onOpenDeparture) }
            }
            if (savedLines.isNotEmpty()) item(key = "lines") { SavedLinesCard(savedLines, spec, onOpenRoute) }

            item(key = "actions") {
                ActionRow(spec) {
                    LabelAction(Icons.Rounded.Search, stringResource(R.string.search), onSearch)
                    PillAction(Icons.Rounded.Map, stringResource(R.string.map), onMap)
                }
            }
            item(key = "settings") {
                Box(contentModifier(spec), contentAlignment = Alignment.Center) {
                    CompactButton(
                        onClick = onSettings,
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.settings)) },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.StationCard(
    stop: HomeStop,
    now: LocalDateTime,
    index: Int,
    spec: TransformationSpec,
    onOpenStop: (Int) -> Unit,
    onOpenDeparture: (Int, StopDeparture) -> Unit,
) {
    HomeCard(spec, index, stop.stopId) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpenStop(stop.stopId) }.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stop.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (stop.departures.isEmpty()) {
            Text(
                stringResource(R.string.no_departures),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (departure in stop.departures) {
            CardRow({ onOpenDeparture(stop.stopId, departure) }) {
                LineBadge(departure.shortName, departure.color, height = 20.dp)
                Text(
                    departure.destination(),
                    Modifier.weight(1f).padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Eta(
                    departure.nextTimes.firstOrNull()?.minutes ?: departure.minutesLeft,
                    !departure.isStatic,
                    now,
                    style = MaterialTheme.typography.labelMedium,
                    mode = stop.look?.mode ?: Mode.BUS,
                    iconSize = 10.dp,
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.SavedLinesCard(lines: List<SavedLine>, spec: TransformationSpec, onOpenRoute: (Int, String) -> Unit) {
    HomeCard(spec, 0, "lines", top = 6.dp) {
        for (line in lines) {
            CardRow({ onOpenRoute(line.route.routeId, line.direction) }) {
                LineBadge(line.route.shortName, line.route.color, height = 20.dp)
                Text(
                    line.label,
                    Modifier.weight(1f).padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.HomeCard(
    spec: TransformationSpec,
    index: Int,
    key: Any,
    top: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        contentModifier(spec)
            .cascade(index, key)
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(top = top, bottom = 6.dp),
        content = content,
    )
}

@Composable
private fun CardRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CardHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = 0.8f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CardTop(shortName: String, color: String, time: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineBadge(shortName, color, height = 26.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { time() }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.FollowCard(follow: FollowState, spec: TransformationSpec, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val nowMs by rememberClock()
    Card(
        onClick = onOpen,
        modifier = itemModifier(spec).squish(press),
        interactionSource = press,
        transformation = surface(spec),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        CardTop(follow.followed.shortName, follow.followed.color) {
            val minutes = follow.minutesAt(nowMs)
            if (minutes == null) Text("…", style = MaterialTheme.typography.titleLarge)
            else Eta(
                minutes, follow.live, LocalDateTime.now(CLUJ_ZONE),
                style = MaterialTheme.typography.titleLarge, idle = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        val walk = follow.walkMinutes?.takeIf { follow.leaveAt != null }
        CardHint(followStatus(follow, nowMs) + walk?.let { " · " + stringResource(R.string.walk_minutes, it) }.orEmpty())
    }
}

@Composable
private fun TransformingLazyColumnItemScope.ReminderCard(reminder: Reminder, spec: TransformationSpec, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val nowMs by rememberClock()
    Card(
        onClick = onOpen,
        modifier = itemModifier(spec).squish(press),
        interactionSource = press,
        transformation = surface(spec),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        CardTop(reminder.followed.shortName, reminder.followed.color) {
            Eta(
                minutesUntil(reminder.atMs, nowMs), false, LocalDateTime.now(CLUJ_ZONE),
                style = MaterialTheme.typography.titleLarge, idle = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        val leaveAt = reminder.leaveAt
        CardHint(
            if (leaveAt != null) stringResource(R.string.leave_at, clockText(leaveAt)) + " · " + reminder.followed.stopName
            else stringResource(R.string.arrives_at_stop, reminder.followed.stopName),
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.JourneyCard(trip: JourneySync, spec: TransformationSpec, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Card(
        onClick = onOpen,
        modifier = itemModifier(spec).squish(press),
        interactionSource = press,
        transformation = surface(spec),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.Navigation, null, Modifier.size(14.dp))
            Text(
                stringResource(R.string.journey_to, trip.title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trip.countsDown) Countdown(trip.arrivalAt!!, MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            trip.line?.let { LineBadge(it, trip.color.orEmpty(), height = 24.dp) }
            FitText(trip.step, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3)
        }
    }
}
