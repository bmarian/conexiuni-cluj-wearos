package online.bmarian.bus.ui.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.logic.groupLabel
import online.bmarian.bus.logic.groupRoutes
import online.bmarian.bus.logic.modeOf
import online.bmarian.bus.logic.readKey
import online.bmarian.bus.logic.tripOf
import online.bmarian.bus.ui.Eta
import online.bmarian.bus.ui.ActionRow
import online.bmarian.bus.ui.LabelAction
import online.bmarian.bus.ui.LineBadge
import online.bmarian.bus.ui.ListScreen
import online.bmarian.bus.ui.Loading
import online.bmarian.bus.ui.LocalAmbient
import online.bmarian.bus.ui.LocalLiveBeat
import online.bmarian.bus.ui.home.CardRow
import online.bmarian.bus.ui.home.HomeCard
import online.bmarian.bus.ui.itemModifier
import online.bmarian.bus.ui.map.ChipText
import online.bmarian.bus.ui.map.MAP_CHIP_TOP
import online.bmarian.bus.ui.map.ME_ZOOM
import online.bmarian.bus.ui.map.MapChip
import online.bmarian.bus.ui.map.MapControls
import online.bmarian.bus.ui.map.MapFrame
import online.bmarian.bus.ui.map.MapLine
import online.bmarian.bus.ui.map.MapVehicle
import online.bmarian.bus.ui.map.TransitMap
import online.bmarian.bus.ui.map.rememberMapCamera
import online.bmarian.bus.ui.map.rememberMapChrome
import online.bmarian.bus.ui.map.rememberUserLocation
import online.bmarian.bus.ui.surface
import online.bmarian.bus.ui.theme.LocalLineLooks

@Composable
private fun groupTitle(key: String): String {
    val index by Services.repository.network.index.collectAsStateWithLifecycle()
    val saved by Services.groups.saved.collectAsStateWithLifecycle()
    return saved.firstOrNull { it.key == key }?.name ?: index?.let { groupLabel(it.groupRoutes(readKey(key))) }.orEmpty()
}

@Composable
private fun groupViewModel(key: String): GroupViewModel {
    val vm: GroupViewModel = viewModel(key = "group-$key") { GroupViewModel(key) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val me by rememberUserLocation()
    LaunchedEffect(me) { vm.locate(me) }
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
    return vm
}

@Composable
fun GroupScreen(key: String, onOpenRoute: (Int, String, Int?) -> Unit, onOpenMap: () -> Unit) {
    val vm = groupViewModel(key)
    val index by Services.repository.network.index.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val beat by vm.beat.collectAsStateWithLifecycle()
    val title = groupTitle(key)
    CompositionLocalProvider(LocalLiveBeat provides beat) {
    ListScreen { spec ->
        item(key = "head") {
            ListHeader(modifier = itemModifier(spec), transformation = surface(spec)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.group_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(title, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val known = index
        if (known == null) {
            item(key = "loading") { Loading(spec) }
        } else {
            item(key = "lines") {
                HomeCard(spec, 0, "lines", top = 6.dp) {
                    vm.lines.forEachIndexed { i, line ->
                        val route = known.route(line.routeId) ?: return@forEachIndexed
                        val row = ui?.rows?.getOrNull(i)
                        CardRow({ onOpenRoute(line.routeId, line.direction, row?.stopId) }) {
                            LineBadge(route.shortName, route.color, height = 20.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                Text(
                                    "→ " + terminalName(route.longName, line.direction),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                row?.stopId?.let { known.stop(it) }?.let { RowNote(stringResource(R.string.from_stop, it.name)) }
                                if (row != null && row.departure == null) RowNote(stringResource(R.string.no_more_today))
                            }
                            val departure = row?.departure
                            val now = ui?.now
                            if (departure != null && now != null) {
                                Eta(
                                    departure.nextTimes.firstOrNull()?.minutes ?: departure.minutesLeft,
                                    !departure.isStatic,
                                    now,
                                    style = MaterialTheme.typography.labelMedium,
                                    mode = modeOf(route.type),
                                    iconSize = 10.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "actions") {
            ActionRow(spec) { LabelAction(Icons.Rounded.Map, stringResource(R.string.map), onOpenMap) }
        }
    }
    }
}

@Composable
private fun RowNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun GroupMapScreen(key: String) {
    val vm = groupViewModel(key)
    LaunchedEffect(vm) { vm.load() }
    val index by Services.repository.network.index.collectAsStateWithLifecycle()
    val shapes by vm.shapes.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val title = groupTitle(key)
    val me by rememberUserLocation()
    val camera = rememberMapCamera()
    val chrome = rememberMapChrome()
    val looks = LocalLineLooks.current

    val known = index
    val lines = remember(known, shapes, looks) {
        vm.lines.mapNotNull { line ->
            val route = known?.route(line.routeId) ?: return@mapNotNull null
            val points = shapes[tripOf(line)]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val trip = tripOf(line)
            MapLine(points.map { it.lat to it.lon }, looks.color(route.shortName, route.color), name = route.shortName, id = trip, tripId = trip)
        }
    }
    val frame = remember(lines) { lines.flatMap { it.points }.takeIf { it.isNotEmpty() }?.let { MapFrame.Fit(it) } }
    val vehicles = vm.lines.flatMap { line ->
        val route = known?.route(line.routeId) ?: return@flatMap emptyList()
        val color = looks.color(route.shortName, route.color)
        live?.vehicles(tripOf(line)).orEmpty().map {
            MapVehicle("${it.id}", it.latitude, it.longitude, route.shortName, color, it.heading, faded = it.atStand, vehicle = it)
        }
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                frame = frame,
                onMapTap = chrome::toggle,
                lines = lines,
                terminals = true,
                vehicles = vehicles,
                me = me,
                ambient = LocalAmbient.current,
            )
            if (title.isNotEmpty()) {
                MapControls(chrome, Modifier.align(Alignment.TopCenter).padding(top = MAP_CHIP_TOP)) {
                    MapChip { ChipText(title) }
                }
            }
            me?.let { fix ->
                MapControls(chrome, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                    FilledTonalIconButton(onClick = { camera.centerOn(fix.lat, fix.lon, ME_ZOOM) }) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.my_location))
                    }
                }
            }
        }
    }
}
