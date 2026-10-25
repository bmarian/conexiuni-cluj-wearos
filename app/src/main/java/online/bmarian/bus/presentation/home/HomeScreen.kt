package online.bmarian.bus.presentation.home

import online.bmarian.bus.presentation.StationIcon
import online.bmarian.bus.presentation.StationLines
import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.WearableRemoteInputExtender
import online.bmarian.bus.BuildConfig
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.bothWays
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.FollowState
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.JourneyMirror
import online.bmarian.bus.follow.JourneySync
import online.bmarian.bus.follow.Reminder
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.Countdown
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.FitText
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.SubHeader
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.cascade
import online.bmarian.bus.presentation.clockText
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.rememberClock
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface
import java.time.LocalDateTime

private const val SEARCH_KEY = "query"
@Composable
fun rememberSearchInput(onQuery: (String) -> Unit): () -> Unit {
    val label = stringResource(R.string.search_label)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(SEARCH_KEY) }?.toString()
        if (!text.isNullOrBlank()) onQuery(text.trim())
    }
    return remember(launcher, label) {
        {
            val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            val input = WearableRemoteInputExtender(RemoteInput.Builder(SEARCH_KEY).setLabel(label))
                .setEmojisAllowed(false)
                .setInputActionType(EditorInfo.IME_ACTION_SEARCH)
                .get()
                .build()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
            launcher.launch(intent)
        }
    }
}

@Composable
fun HomeScreen(
    state: TransformingLazyColumnState,
    onOpenStop: (Int) -> Unit,
    onOpenRoute: (Int, String) -> Unit,
    onOpenBus: (Followed) -> Unit,
    onSearch: (String) -> Unit,
    onNearby: () -> Unit,
    onBrowseRoutes: () -> Unit,
    onBrowseStops: () -> Unit,
    onCopyFavorites: () -> Unit,
    onOpenJourney: () -> Unit,
) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val stops by repository.stops.collectAsStateWithLifecycle()
    val favoriteStopIds by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val journey by JourneyMirror.state.collectAsStateWithLifecycle()
    val cities by Cities.list.collectAsStateWithLifecycle()
    val city by Cities.current.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val search = rememberSearchInput(onSearch)

    LaunchedEffect(Unit) {
        repository.loadCached()
        repository.refreshNetwork()
    }

    val favoriteStops = remember(stops, favoriteStopIds) {
        val byId = stops.associateBy { it.stopId }
        favoriteStopIds.mapNotNull { byId[it] }
    }
    val favoriteRouteRows = remember(routes, favoriteRoutes) {
        val byId = routes.associateBy { it.routeId }
        // Both directions saved: one row that opens the direction nearest the user.
        favoriteRoutes.groupBy { it.routeId }.mapNotNull { (routeId, favorites) ->
            val route = byId[routeId] ?: return@mapNotNull null
            if (favorites.size > 1) Triple(route, "auto", bothWays(route.longName))
            else Triple(route, favorites[0].direction, "→ " + terminalName(route.longName, favorites[0].direction))
        }
    }
    val cityName = remember(cities, city) { if (cities.size > 1) cities.firstOrNull { it.slug == city }?.name else null }

    ListScreen(state = state) { spec ->
        item { Title(stringResource(R.string.app_name), spec) }
        if (cityName != null) {
            item {
                Text(
                    cityName,
                    modifier = contentModifier(spec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        journey?.let { trip -> item { JourneyCard(trip, spec, onOpenJourney) { JourneyMirror.end(context, trip.id) } } }
        items(follows.size, key = { "follow-${follows[it].id}" }) { i ->
            val follow = follows[i]
            FollowCard(follow, spec, onOpen = { onOpenBus(follow.followed) }, onStop = { FollowManager.stop(context, follow.id) })
        }
        items(reminders.size, key = { "reminder-${reminders[it].id}" }) { i ->
            val reminder = reminders[i]
            ReminderCard(reminder, spec, onOpen = { onOpenBus(reminder.followed) }, onDelete = { Reminders.remove(context, reminder.id) })
        }
        item {
            ActionRow(spec) {
                PillAction(Icons.Rounded.Search, stringResource(R.string.search), search)
                PillAction(Icons.Rounded.NearMe, stringResource(R.string.nearby_stops), onNearby)
            }
        }

        if (favoriteStops.isEmpty() && favoriteRouteRows.isEmpty()) {
            item { Message(stringResource(R.string.no_favorites), spec) }
        } else {
            item { SubHeader(stringResource(R.string.favorites), spec) }
        }
        items(favoriteStops.size, key = { "fs-${favoriteStops[it].stopId}" }) { i ->
            val stop = favoriteStops[i]
            val look = Services.repository.network.index.value?.look(stop.stopId)
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = { onOpenStop(stop.stopId) },
                modifier = itemModifier(spec).squish(press).cascade(i, "list"),
                interactionSource = press,
                transformation = surface(spec),
                icon = if (look == null) ({ StationIcon(null, size = 26.dp) }) else null,
                label = { Text(stop.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = if (look != null) ({ StationLines(look) }) else null,
            )
        }
        items(favoriteRouteRows.size, key = { "fr-${favoriteRouteRows[it].first.routeId}-${favoriteRouteRows[it].second}" }) { i ->
            val (route, direction, label) = favoriteRouteRows[i]
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = { onOpenRoute(route.routeId, direction) },
                modifier = itemModifier(spec).squish(press).cascade(i, "list"),
                interactionSource = press,
                transformation = surface(spec),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineBadge(route.shortName, route.color)
                    Text(label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item { SubHeader(stringResource(R.string.explore), spec) }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onBrowseRoutes,
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.DirectionsBus, contentDescription = null) },
                label = { Text(stringResource(R.string.all_routes)) },
            )
        }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onBrowseStops,
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                label = { Text(stringResource(R.string.all_stops)) },
            )
        }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onCopyFavorites,
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.QrCode2, contentDescription = null) },
                label = { Text(stringResource(R.string.copy_favorites)) },
            )
        }
        item {
            Column(contentModifier(spec).padding(top = 6.dp, start = 16.dp, end = 16.dp)) {
                Text(
                    stringResource(R.string.credits),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CardLabel(icon: ImageVector, text: String, time: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        time()
    }
}

@Composable
private fun BusLine(followed: Followed) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LineBadge(followed.shortName, followed.color, height = 24.dp)
        FitText("→ ${followed.destination}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StopLine(name: String) {
    Text(
        stringResource(R.string.from_stop, name),
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = 0.75f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TransformingLazyColumnItemScope.FollowCard(follow: FollowState, spec: TransformationSpec, onOpen: () -> Unit, onStop: () -> Unit) {
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
        // Still waiting for the user to set off.
        val leave = follow.leaveIn(nowMs)
        CardLabel(
            if (leave != null) Icons.AutoMirrored.Rounded.DirectionsWalk else Icons.Rounded.NotificationsActive,
            when {
                leave == null -> stringResource(R.string.following)
                leave <= 0 -> stringResource(R.string.leave_now)
                else -> stringResource(R.string.leave_in, stringResource(R.string.eta_minutes, leave))
            },
        ) {
            val at = follow.arrivalAt
            if (at != null) Eta(at, follow.live) else follow.minutes?.let { Eta(it, follow.live, LocalDateTime.now(CLUJ_ZONE)) }
        }
        BusLine(follow.followed)
        StopLine(follow.followed.stopName)
        CompactButton(
            onClick = onStop,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            icon = { Icon(Icons.Rounded.Close, contentDescription = null) },
            label = { Text(stringResource(R.string.stop_following)) },
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.ReminderCard(reminder: Reminder, spec: TransformationSpec, onOpen: () -> Unit, onDelete: () -> Unit) {
    val press = remember { MutableInteractionSource() }
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
        val leaveAt = reminder.leaveAt
        CardLabel(
            if (leaveAt != null) Icons.AutoMirrored.Rounded.DirectionsWalk else Icons.Rounded.NotificationsActive,
            if (leaveAt != null) stringResource(R.string.leave_at, clockText(leaveAt)) else stringResource(R.string.reminder),
        ) { Eta(reminder.atMs, live = false) }
        BusLine(reminder.followed)
        StopLine(reminder.followed.stopName)
        CompactButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            label = { Text(stringResource(R.string.remind_delete)) },
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.JourneyCard(trip: JourneySync, spec: TransformationSpec, onOpen: () -> Unit, onEnd: () -> Unit) {
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
        CardLabel(Icons.Rounded.Navigation, stringResource(R.string.journey_to, trip.title)) {
            if (trip.countsDown) Countdown(trip.arrivalAt!!, MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            trip.line?.let { LineBadge(it, trip.color.orEmpty(), height = 24.dp) }
            FitText(trip.step, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3)
        }
        if (!trip.countsDown) trip.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trip.etaAt?.let {
            Text(
                stringResource(R.string.nav_arrive_at, clockText(it)),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        CompactButton(
            onClick = onEnd,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            icon = { Icon(Icons.Rounded.Close, contentDescription = null) },
            label = { Text(stringResource(R.string.end_journey)) },
        )
    }
}
