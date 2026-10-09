package online.bmarian.bus.presentation.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.logic.formatMeters
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLabel
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.stop.MapChip
import online.bmarian.bus.presentation.surface
import online.bmarian.bus.presentation.theme.Palette
import kotlin.math.ceil

// Same reach and count as the website's nearby list.
private const val NEARBY_RADIUS_METERS = 700.0
private const val NEARBY_COUNT = 6
// The website's walking pace, for the minutes on foot.
private const val WALKING_METERS_PER_MINUTE = 80.0

// GPS wanders by a few tens of metres standing still; past this the list is worked out again.
private const val MOVED_METERS = 50.0
// How long to wait for a first position before saying location is off.
private const val FIRST_FIX_TIMEOUT_MS = 20_000L

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
fun NearbyScreen(onOpenStop: (Int) -> Unit, onOpenMap: () -> Unit) {
    val context = LocalContext.current
    val stops by Services.repository.stops.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(UserLocation.permitted(context)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permitted = result.values.any { it }
        asked = true
    }
    // The last place first, so the list is there at once; it only moves when the wearer does.
    var shown by remember { mutableStateOf(if (permitted) UserLocation.saved(context) else null) }
    var gaveUp by remember { mutableStateOf(false) }
    val updates = remember(permitted) { UserLocation.updates(context) }
    val latest by updates.collectAsStateWithLifecycle(null)

    LaunchedEffect(Unit) {
        Services.repository.refreshStops()
        if (!permitted) launcher.launch(LOCATION_PERMISSIONS)
    }
    LaunchedEffect(latest) {
        val fix = latest ?: return@LaunchedEffect
        val current = shown
        if (current == null || haversineMeters(current.lat, current.lon, fix.lat, fix.lon) > MOVED_METERS) {
            shown = fix
            UserLocation.save(context, fix)
        }
    }
    LaunchedEffect(permitted, shown, gaveUp) {
        if (permitted && shown == null && !gaveUp) {
            delay(FIRST_FIX_TIMEOUT_MS)
            if (shown == null) gaveUp = true
        }
    }

    val here = shown
    val nearby = remember(stops, here) {
        if (here == null) emptyList()
        else stops.map { it to haversineMeters(here.lat, here.lon, it.lat, it.lon) }
            .filter { it.second <= NEARBY_RADIUS_METERS }
            .sortedBy { it.second }
            .take(NEARBY_COUNT)
    }

    ListScreen { spec ->
        item { Title(stringResource(R.string.nearby_stops), spec) }
        when {
            permitted && here != null -> {
                item {
                    ActionRow(spec) { PillAction(Icons.Rounded.Map, stringResource(R.string.open_map), onOpenMap) }
                }
                if (nearby.isEmpty()) item { Message(stringResource(R.string.nearby_none), spec) }
                items(nearby.size, key = { nearby[it].first.stopId }) { i ->
                    val (stop, meters) = nearby[i]
                    FilledTonalButton(
                        onClick = { onOpenStop(stop.stopId) },
                        modifier = itemModifier(spec),
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                        label = { Text(stop.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(stringResource(R.string.walk_distance, formatMeters(meters), ceil(meters / WALKING_METERS_PER_MINUTE).toInt().coerceAtLeast(1)))
                        },
                    )
                }
            }
            (permitted && !gaveUp) || (!permitted && !asked) -> item { Loading(spec) }
            else -> {
                item { Message(stringResource(R.string.nearby_unavailable), spec) }
                item {
                    FilledTonalButton(
                        onClick = { if (permitted) gaveUp = false else launcher.launch(LOCATION_PERMISSIONS) },
                        modifier = itemModifier(spec),
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
                        label = { Text(stringResource(R.string.nearby_enable)) },
                    )
                }
            }
        }
    }
}

private const val NEARBY_MAP_ZOOM = 13.5f

/** The same stops as the list, on the map with their names; a tap opens one. */
@Composable
fun NearbyMapScreen(onOpenStop: (Int) -> Unit) {
    val context = LocalContext.current
    val stops by Services.repository.stops.collectAsStateWithLifecycle()
    val live by rememberUserLocation()
    var here by remember { mutableStateOf(UserLocation.saved(context)) }
    val camera = rememberMapCamera()

    LaunchedEffect(live) {
        val fix = live ?: return@LaunchedEffect
        val current = here
        if (current == null || haversineMeters(current.lat, current.lon, fix.lat, fix.lon) > MOVED_METERS) {
            here = fix
            UserLocation.save(context, fix)
        }
    }

    val center = here
    val around = remember(stops, center) {
        if (center == null) emptyList()
        else stops.map { it to haversineMeters(center.lat, center.lon, it.lat, it.lon) }
            .filter { it.second <= NEARBY_RADIUS_METERS }
            .sortedBy { it.second }
            .take(NEARBY_COUNT)
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                // All six stops and you in view, as close in as that allows.
                frame = center?.takeIf { stops.isNotEmpty() }?.let { here ->
                    MapFrame.Fit(around.map { (stop, _) -> stop.lat to stop.lon } + (here.lat to here.lon), side = 0.22, top = 0.38, bottom = 0.34)
                },
                stops = around.map { (stop, _) -> MapStop("${stop.stopId}", stop.lat, stop.lon, Palette.StopGray) },
                // Nearest first, drawn on top where two names meet.
                labels = around.mapIndexed { i, (stop, _) -> MapLabel("${stop.stopId}", stop.lat, stop.lon, stop.name, live = false, rank = i) },
                me = live,
                ambient = LocalAmbient.current,
                onStopTap = { id -> id.toIntOrNull()?.let(onOpenStop) },
            )
            MapChip(Modifier.align(Alignment.TopCenter).padding(top = 30.dp)) {
                Text(stringResource(R.string.nearby_stops), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            if (center == null) {
                MapChip(Modifier.align(Alignment.Center)) {
                    Text(stringResource(R.string.nearby_unavailable), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
            live?.let { fix ->
                FilledTonalIconButton(
                    onClick = { camera.centerOn(fix.lat, fix.lon, NEARBY_MAP_ZOOM) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).size(44.dp),
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.my_location))
                }
            }
        }
    }
}

