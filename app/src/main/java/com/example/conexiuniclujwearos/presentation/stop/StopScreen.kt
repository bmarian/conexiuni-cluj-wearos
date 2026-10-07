package com.example.conexiuniclujwearos.presentation.stop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.conexiuniclujwearos.R
import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.data.directionFromTripId
import com.example.conexiuniclujwearos.logic.formatMinutesFromNow
import com.example.conexiuniclujwearos.presentation.ActionRow
import com.example.conexiuniclujwearos.presentation.FavoriteToggle
import com.example.conexiuniclujwearos.presentation.Heading
import com.example.conexiuniclujwearos.presentation.Icons
import com.example.conexiuniclujwearos.presentation.ListRow
import com.example.conexiuniclujwearos.presentation.ListScreen
import com.example.conexiuniclujwearos.presentation.Note
import com.example.conexiuniclujwearos.presentation.RouteBadge
import com.example.conexiuniclujwearos.presentation.map.MapFrame
import com.example.conexiuniclujwearos.presentation.map.MapLine
import com.example.conexiuniclujwearos.presentation.map.MapStop
import com.example.conexiuniclujwearos.presentation.map.MapVehicle
import com.example.conexiuniclujwearos.presentation.map.TransitMap
import com.example.conexiuniclujwearos.presentation.map.rememberMapCamera
import com.example.conexiuniclujwearos.presentation.map.rememberUserLocation
import com.example.conexiuniclujwearos.presentation.theme.Palette
import com.example.conexiuniclujwearos.presentation.theme.routeColor
import com.example.conexiuniclujwearos.presentation.transformed

// Far enough out to see the buses that are a few minutes away.
private const val STOP_MAP_ZOOM = 15f

@Composable
fun stopViewModel(stopId: Int): StopViewModel = viewModel(key = "stop-$stopId") { StopViewModel(stopId) }

@Composable
fun LiveWhileVisible(vm: StopViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
}

@Composable
fun StopScreen(
    vm: StopViewModel,
    onOpenRoute: (routeId: Int, direction: String) -> Unit,
    onOpenMap: () -> Unit,
) {
    LiveWhileVisible(vm)
    val name by vm.name.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favoriteStops by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val isFavorite = vm.stopId in favoriteStops
    val nowLabel = stringResource(R.string.now)

    val sorted = remember(ui, favoriteRoutes) {
        ui?.departures?.sortedWith(compareBy({ if (vm.isDepartureFavorite(it)) 0 else 1 }, { it.minutesLeft }))
    }

    ListScreen { spec ->
        item { Heading(name ?: "…", transformed(spec), kicker = stringResource(R.string.bus_stop)) }
        item {
            FavoriteToggle(
                isFavorite = isFavorite,
                onToggle = { Services.favorites.toggleStop(vm.stopId) },
                addLabel = stringResource(R.string.add_to_favorites),
                removeLabel = stringResource(R.string.remove_from_favorites),
                modifier = transformed(spec),
            )
        }
        when {
            failed -> {
                item { Note(stringResource(R.string.load_failed), transformed(spec)) }
                item { ActionRow(stringResource(R.string.retry), onClick = vm::load, modifier = transformed(spec)) }
            }
            sorted == null -> item { Note(stringResource(R.string.loading_stop), transformed(spec)) }
            sorted.isEmpty() -> item { Note(stringResource(R.string.no_schedule), transformed(spec)) }
            else -> items(sorted.size, key = { sorted[it].shortName }) { i ->
                val departure = sorted[i]
                val now = ui!!.now
                val first = departure.nextTimes.firstOrNull()?.minutes ?: departure.minutesLeft
                val later = departure.nextTimes.drop(1).joinToString(" · ") { formatMinutesFromNow(it.minutes, now, nowLabel) }
                ListRow(
                    title = departure.longName.substringAfterLast(" - "),
                    sub = later,
                    onClick = { onOpenRoute(departure.routeId, directionFromTripId(departure.tripId)) },
                    modifier = transformed(spec),
                    leading = { RouteBadge(departure.shortName, departure.color) },
                    trailing = {
                        Text(
                            (if (departure.isStatic) "~" else "") + formatMinutesFromNow(first, now, nowLabel),
                            color = if (departure.isStatic) Palette.Text else Palette.Accent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        item { ActionRow(stringResource(R.string.map), onClick = onOpenMap, modifier = transformed(spec), icon = Icons.Map) }
    }
}

@Composable
fun StopMapScreen(vm: StopViewModel) {
    LiveWhileVisible(vm)
    val location by vm.location.collectAsStateWithLifecycle()
    val layer by vm.mapLayer.collectAsStateWithLifecycle()
    val me by rememberUserLocation()
    val camera = rememberMapCamera()
    val frame = location?.let { MapFrame.Focus(it.first, it.second, STOP_MAP_ZOOM) }
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                frame = frame,
                lines = layer.lines.map { MapLine(it.points, routeColor(it.color)) },
                stops = listOfNotNull(location?.let { MapStop("${vm.stopId}", it.first, it.second, Palette.AccentStrong, selected = true) }),
                vehicles = layer.vehicles.map { MapVehicle(it.id, it.lat, it.lon, it.name, routeColor(it.color), it.heading) },
                me = me,
            )
        }
    }
}
