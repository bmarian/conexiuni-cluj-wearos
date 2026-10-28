package online.bmarian.bus.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.logic.NEARBY_RADIUS_METERS
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.ui.LocalAmbient
import online.bmarian.bus.ui.map.ChipText
import online.bmarian.bus.ui.map.MAP_CHIP_TOP
import online.bmarian.bus.ui.map.MapChip
import online.bmarian.bus.ui.map.MapControls
import online.bmarian.bus.ui.map.MapFrame
import online.bmarian.bus.ui.map.MapLabel
import online.bmarian.bus.ui.map.TransitMap
import online.bmarian.bus.ui.map.rememberMapCamera
import online.bmarian.bus.ui.map.rememberMapChrome
import online.bmarian.bus.ui.map.rememberUserLocation
import online.bmarian.bus.ui.map.stationMapStop

private const val NEARBY_COUNT = 6
private const val NEARBY_MAP_ZOOM = 13.5f

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

    val chrome = rememberMapChrome()
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                onMapTap = chrome::toggle,
                frame = center?.takeIf { stops.isNotEmpty() }?.let { here ->
                    MapFrame.Fit(around.map { (stop, _) -> stop.lat to stop.lon } + (here.lat to here.lon), side = 0.22, top = 0.38, bottom = 0.34)
                },
                stops = around.map { (stop, _) -> stationMapStop(stop.stopId, stop.lat, stop.lon) },
                labels = around.mapIndexed { i, (stop, _) -> MapLabel("${stop.stopId}", stop.lat, stop.lon, stop.name, live = false, rank = i) },
                me = live,
                ambient = LocalAmbient.current,
                onStopTap = { id -> id.toIntOrNull()?.let(onOpenStop) },
            )
            MapControls(chrome, Modifier.align(Alignment.TopCenter).padding(top = MAP_CHIP_TOP)) {
                MapChip { ChipText(stringResource(R.string.nearby_stops)) }
            }
            if (center == null) {
                MapChip(Modifier.align(Alignment.Center)) { ChipText(stringResource(R.string.nearby_unavailable)) }
            }
            live?.let { fix ->
                MapControls(chrome, Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) {
                    FilledTonalIconButton(
                        onClick = { camera.centerOn(fix.lat, fix.lon, NEARBY_MAP_ZOOM) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.my_location))
                    }
                }
            }
        }
    }
}
