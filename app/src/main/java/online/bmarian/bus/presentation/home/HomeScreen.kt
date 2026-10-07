package online.bmarian.bus.presentation.home

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.WearableRemoteInputExtender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.stopSnapshot
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.computeStopDepartures
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.SubHeader
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.surface
import java.time.Instant
import java.time.LocalDateTime

private const val SEARCH_KEY = "query"
private const val SUMMARY_REFRESH_MS = 60_000L

class StopSummary(val departures: List<StopDeparture>, val now: LocalDateTime)

// Next departures at the favorite stops, like the ETAs Google Maps puts on Home and Work.
class HomeViewModel : ViewModel() {
    val summaries = MutableStateFlow<Map<Int, StopSummary>>(emptyMap())

    suspend fun live() {
        Services.favorites.stops.collectLatest { ids ->
            val repository = Services.repository
            for (id in ids) {
                if (id in summaries.value) continue
                val cached = repository.cachedStopInfo(id) ?: continue
                val nowMs = System.currentTimeMillis()
                val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
                summaries.value += id to StopSummary(computeStopDepartures(cached, emptyMap(), emptyMap(), now, nowMs), now)
            }
            while (true) {
                for (id in ids) {
                    val snapshot = runCatching { stopSnapshot(id) }.getOrNull() ?: continue
                    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(snapshot.takenAtMs), CLUJ_ZONE)
                    summaries.value += id to StopSummary(snapshot.departures, now)
                }
                delay(SUMMARY_REFRESH_MS)
            }
        }
    }
}

/** Opens the watch's voice and keyboard input, and hands back what was said or typed. */
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
    onOpenFollowed: () -> Unit,
    onSearch: (String) -> Unit,
    onNearby: () -> Unit,
    onBrowseRoutes: () -> Unit,
    onBrowseStops: () -> Unit,
    onCopyFavorites: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val stops by repository.stops.collectAsStateWithLifecycle()
    val favoriteStopIds by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val following by FollowManager.state.collectAsStateWithLifecycle()
    val search = rememberSearchInput(onSearch)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
    LaunchedEffect(Unit) {
        repository.loadCached()
        repository.refreshRoutes()
        repository.refreshStops()
    }

    val favoriteStops = remember(stops, favoriteStopIds) {
        val byId = stops.associateBy { it.stopId }
        favoriteStopIds.mapNotNull { byId[it] }
    }
    val favoriteRouteRows = remember(routes, favoriteRoutes) {
        val byId = routes.associateBy { it.routeId }
        favoriteRoutes.mapNotNull { fav -> byId[fav.routeId]?.let { Triple(it, fav.direction, terminalName(it.longName, fav.direction)) } }
    }

    ListScreen(state = state) { spec ->
        item { Title(stringResource(R.string.app_name), spec) }
        following?.let { follow ->
            item {
                Card(
                    onClick = onOpenFollowed,
                    modifier = itemModifier(spec),
                    transformation = surface(spec),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(stringResource(R.string.following), style = MaterialTheme.typography.labelSmall)
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
            val summary = summaries[stop.stopId]
            FilledTonalButton(
                onClick = { onOpenStop(stop.stopId) },
                modifier = itemModifier(spec),
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                label = { Text(stop.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = summary?.takeIf { it.departures.isNotEmpty() }?.let { s ->
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (departure in s.departures.take(2)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LineBadge(departure.shortName, departure.color, height = 16.dp)
                                    Eta(departure.minutesLeft, !departure.isStatic, s.now, MaterialTheme.typography.labelSmall, arcs = false)
                                }
                            }
                        }
                    }
                },
            )
        }
        items(favoriteRouteRows.size, key = { "fr-${favoriteRouteRows[it].first.routeId}-${favoriteRouteRows[it].second}" }) { i ->
            val (route, direction, destination) = favoriteRouteRows[i]
            FilledTonalButton(
                onClick = { onOpenRoute(route.routeId, direction) },
                modifier = itemModifier(spec),
                transformation = surface(spec),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineBadge(route.shortName, route.color)
                    Text("→ $destination", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item { SubHeader(stringResource(R.string.explore), spec) }
        item {
            FilledTonalButton(
                onClick = onBrowseRoutes,
                modifier = itemModifier(spec),
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.DirectionsBus, contentDescription = null) },
                label = { Text(stringResource(R.string.all_routes)) },
            )
        }
        item {
            FilledTonalButton(
                onClick = onBrowseStops,
                modifier = itemModifier(spec),
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                label = { Text(stringResource(R.string.all_stops)) },
            )
        }
        item {
            FilledTonalButton(
                onClick = onCopyFavorites,
                modifier = itemModifier(spec),
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
            }
        }
    }
}
