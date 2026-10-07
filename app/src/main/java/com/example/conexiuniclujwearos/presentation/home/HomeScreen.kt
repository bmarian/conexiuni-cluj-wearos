package com.example.conexiuniclujwearos.presentation.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.Text
import com.example.conexiuniclujwearos.R
import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.data.terminalName
import com.example.conexiuniclujwearos.presentation.BrowseIcon
import com.example.conexiuniclujwearos.presentation.Icons
import com.example.conexiuniclujwearos.presentation.ListRow
import com.example.conexiuniclujwearos.presentation.ListScreen
import com.example.conexiuniclujwearos.presentation.Note
import com.example.conexiuniclujwearos.presentation.RoundIcon
import com.example.conexiuniclujwearos.presentation.RouteBadge
import com.example.conexiuniclujwearos.presentation.SectionLabel
import com.example.conexiuniclujwearos.presentation.StopIcon
import com.example.conexiuniclujwearos.presentation.theme.Palette
import com.example.conexiuniclujwearos.presentation.transformed

@Composable
fun HomeScreen(
    state: TransformingLazyColumnState,
    onOpenStop: (Int) -> Unit,
    onOpenRoute: (Int, String) -> Unit,
    onNearby: () -> Unit,
    onBrowseRoutes: () -> Unit,
    onBrowseStops: () -> Unit,
    onCopyFavorites: () -> Unit,
) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val stops by repository.stops.collectAsStateWithLifecycle()
    val favoriteStopIds by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()

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

    ListScreen(state) { spec ->
        if (favoriteStops.isEmpty() && favoriteRouteRows.isEmpty()) {
            item { Note(stringResource(R.string.no_favorites), transformed(spec)) }
        }
        if (favoriteStops.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.favorite_stops), transformed(spec)) }
            items(favoriteStops.size, key = { "fs-${favoriteStops[it].stopId}" }) { i ->
                val stop = favoriteStops[i]
                ListRow(stop.name, onClick = { onOpenStop(stop.stopId) }, modifier = transformed(spec), leading = { StopIcon() })
            }
        }
        if (favoriteRouteRows.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.favorite_routes), transformed(spec)) }
            items(favoriteRouteRows.size, key = { "fr-${favoriteRouteRows[it].first.routeId}-${favoriteRouteRows[it].second}" }) { i ->
                val (route, direction, destination) = favoriteRouteRows[i]
                ListRow(
                    title = "→ $destination",
                    onClick = { onOpenRoute(route.routeId, direction) },
                    modifier = transformed(spec),
                    leading = { RouteBadge(route.shortName, route.color) },
                )
            }
        }
        item { Spacer(transformed(spec).height(6.dp)) }
        item {
            ListRow(stringResource(R.string.nearby_stops), onClick = onNearby, modifier = transformed(spec), leading = { RoundIcon(Icons.Location, Palette.Accent, Palette.AccentWash) })
        }
        item {
            ListRow(stringResource(R.string.all_routes), onClick = onBrowseRoutes, modifier = transformed(spec), leading = { BrowseIcon(Icons.Map) })
        }
        item {
            ListRow(stringResource(R.string.all_stops), onClick = onBrowseStops, modifier = transformed(spec), leading = { StopIcon() })
        }
        item {
            ListRow(stringResource(R.string.copy_favorites), onClick = onCopyFavorites, modifier = transformed(spec), leading = { BrowseIcon(Icons.Qr) })
        }
        item {
            Text(
                stringResource(R.string.credits),
                modifier = transformed(spec).fillMaxWidth().padding(top = 10.dp, start = 16.dp, end = 16.dp),
                color = Palette.Faint,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
