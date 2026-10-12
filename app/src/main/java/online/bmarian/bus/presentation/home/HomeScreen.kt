package online.bmarian.bus.presentation.home

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.WearableRemoteInputExtender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import online.bmarian.bus.BuildConfig
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.stopSnapshot
import online.bmarian.bus.data.bothWays
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.FollowState
import online.bmarian.bus.follow.JourneyMirror
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.Countdown
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.SubHeader
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.cascade
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface
import java.time.Instant
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
    onOpenFollowed: (FollowState) -> Unit,
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
    val journey by JourneyMirror.state.collectAsStateWithLifecycle()
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
            else Triple(route, favorites[0].direction, "→\u00A0" + terminalName(route.longName, favorites[0].direction))
        }
    }

    ListScreen(state = state) { spec ->
        item { Title(stringResource(R.string.app_name), spec) }
        journey?.let { trip ->
            item {
                val press = remember { MutableInteractionSource() }
                Card(
                    onClick = onOpenJourney,
                    modifier = itemModifier(spec).squish(press),
                    interactionSource = press,
                    transformation = surface(spec),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text(
                        stringResource(R.string.journey_to, trip.title),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        trip.line?.let { LineBadge(it, trip.color.orEmpty(), height = 24.dp) }
                        Text(
                            trip.step,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        trip.arrivalAt?.let { Countdown(it, MaterialTheme.typography.labelLarge) }
                    }
                    trip.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    CompactButton(
                        onClick = { JourneyMirror.end(context, trip.id) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        icon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                        label = { Text(stringResource(R.string.end_journey)) },
                    )
                }
            }
        }
        items(follows.size, key = { "follow-${follows[it].id}" }) { i ->
            val follow = follows[i]
            run {
                val press = remember { MutableInteractionSource() }
                Card(
                    onClick = { onOpenFollowed(follow) },
                    modifier = itemModifier(spec).squish(press),
                    interactionSource = press,
                    transformation = surface(spec),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    // A phone reminder still waiting for the user to set off.
                    val leave = follow.leaveIn()
                    Text(
                        when {
                            leave == null -> stringResource(R.string.following)
                            leave <= 0 -> stringResource(R.string.leave_now)
                            else -> stringResource(R.string.leave_in, stringResource(R.string.eta_minutes, leave))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (leave != null) FontWeight.Bold else null,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LineBadge(follow.followed.shortName, follow.followed.color, height = 24.dp)
                        Text(
                            follow.followed.destination,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        follow.minutes?.let { Eta(it, follow.live, LocalDateTime.now(CLUJ_ZONE)) }
                    }
                }
            }
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
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = { onOpenStop(stop.stopId) },
                modifier = itemModifier(spec).squish(press).cascade(i, "list"),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                label = { Text(stop.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
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
                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
