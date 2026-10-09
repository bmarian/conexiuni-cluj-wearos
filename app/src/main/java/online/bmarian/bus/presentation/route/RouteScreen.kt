package online.bmarian.bus.presentation.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.data.tripIdFor
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.daySchedule
import online.bmarian.bus.logic.departureMinutes
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.logic.minutesOfDay
import online.bmarian.bus.logic.stopsForDirection
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.LineHeader
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.map.MapCamera
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLabel
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.rememberOpenOnPhone
import online.bmarian.bus.presentation.stop.MapChip
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.routeColor
import java.time.LocalDateTime

// Opened from a stop, the map starts on that stop.
private const val FROM_STOP_ZOOM = 13f
private const val ME_ZOOM = 14f
private val MAP_BUTTON = 44.dp
private val SMALL_ACTION = 36.dp

@Composable
fun routeViewModel(routeId: Int, direction: String, fromStopId: Int?): RouteViewModel =
    viewModel(key = "route-$routeId-${fromStopId ?: ""}") { RouteViewModel(routeId, direction, fromStopId) }

@Composable
fun RouteScreen(vm: RouteViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }

    val info by vm.shapeInfo.collectAsStateWithLifecycle()
    val direction by vm.direction.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    var timetableOpen by rememberSaveable { mutableStateOf(false) }
    val camera = rememberMapCamera()

    BackHandler(enabled = timetableOpen) { timetableOpen = false }

    val terminal = info?.let { terminalName(it.longName, direction) }.orEmpty()
    val canSwap = info != null && vm.hasDirection(if (direction == "0") "1" else "0")

    if (timetableOpen) {
        TimetableView(vm, info, direction, terminal, canSwap, onMap = { timetableOpen = false })
    } else {
        RouteMapView(vm, camera, info, terminal, failed, canSwap, onTimetable = { timetableOpen = true })
    }
}

@Composable
private fun RouteMapView(
    vm: RouteViewModel,
    camera: MapCamera,
    info: ShapeInfo?,
    terminal: String,
    failed: Boolean,
    canSwap: Boolean,
    onTimetable: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val me by rememberUserLocation()
    val favoriteStops by Services.favorites.stops.collectAsStateWithLifecycle()
    val nowLabel = stringResource(R.string.now)
    val soonLabel = stringResource(R.string.soon)
    val color = routeColor(info?.color ?: "")
    val fromStopId = vm.fromStopId

    val current = ui
    val stops = current?.stops.orEmpty()
    val frame = remember(current?.shape, stops) {
        val from = stops.find { it.stop.stopId == fromStopId }?.stop
        when {
            from != null && from.lat != 0.0 -> MapFrame.Focus(from.lat, from.lon, FROM_STOP_ZOOM)
            current?.shape != null -> MapFrame.Fit(current.shape.shape.map { it.lat to it.lon })
            stops.isNotEmpty() -> MapFrame.Fit(stops.filter { it.stop.lat != 0.0 }.map { it.stop.lat to it.stop.lon })
            else -> null
        }
    }
    val labels = remember(current, nowLabel) {
        if (current == null) emptyList()
        else current.stops.mapIndexedNotNull { idx, stop ->
            val next = current.arrivals.getOrNull(idx)?.firstOrNull() ?: return@mapIndexedNotNull null
            if (stop.stop.lat == 0.0 || stop.stop.lon == 0.0) return@mapIndexedNotNull null
            MapLabel(
                id = "${stop.stop.stopId}-$idx",
                lat = stop.stop.lat,
                lon = stop.stop.lon,
                text = if (next.soon) soonLabel else formatMinutesFromNow(next.minutes, current.now, nowLabel),
                live = next.isLive,
                rank = if (stop.stop.stopId == fromStopId) Int.MIN_VALUE else next.minutes,
            )
        }
    }
    val mapStops = remember(stops, favoriteStops) {
        stops.filter { it.stop.lat != 0.0 }.map {
            val id = it.stop.stopId
            MapStop(
                id = "$id",
                lat = it.stop.lat,
                lon = it.stop.lon,
                color = when {
                    id == fromStopId -> Palette.AccentStrong
                    id in favoriteStops -> Palette.Heart
                    else -> Palette.StopGray
                },
                selected = id == fromStopId,
            )
        }
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                frame = frame,
                lines = current?.shape?.let { listOf(MapLine(it.shape.map { p -> p.lat to p.lon }, color)) }.orEmpty(),
                stops = mapStops,
                labels = labels,
                vehicles = current?.vehicles.orEmpty().map {
                    MapVehicle("${it.vehicle.id}", it.vehicle.latitude, it.vehicle.longitude, it.routeShortName, color, it.heading)
                },
                me = me,
                ambient = LocalAmbient.current,
            )
            if (info != null) {
                MapChip(Modifier.align(Alignment.TopCenter).padding(top = 30.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        LineBadge(info.shortName, info.color, height = 20.dp)
                        if (terminal.isNotEmpty()) {
                            Text("→\u00A0$terminal", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val note = when {
                failed -> stringResource(R.string.load_failed)
                info == null -> stringResource(R.string.loading_route)
                current != null && current.stops.isEmpty() -> stringResource(R.string.no_schedule)
                else -> null
            }
            if (note != null) {
                MapChip(Modifier.align(Alignment.Center)) {
                    Text(note, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
            if (!LocalAmbient.current) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    me?.let { fix ->
                        FilledTonalIconButton(
                            onClick = { camera.centerOn(fix.lat, fix.lon, ME_ZOOM) },
                            modifier = Modifier.size(MAP_BUTTON),
                        ) { Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.my_location)) }
                    }
                    FilledIconButton(onClick = onTimetable, modifier = Modifier.size(MAP_BUTTON)) {
                        Icon(Icons.Rounded.Schedule, contentDescription = stringResource(R.string.timetable))
                    }
                    if (canSwap) {
                        FilledTonalIconButton(onClick = vm::swap, modifier = Modifier.size(MAP_BUTTON)) {
                            Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.switch_direction))
                        }
                    }
                }
            }
        }
    }
}

private class HourRow(val hour: String, val minutes: List<Pair<String, Boolean>>)

@Composable
private fun TimetableView(vm: RouteViewModel, info: ShapeInfo?, direction: String, terminal: String, canSwap: Boolean, onMap: () -> Unit) {
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now = ui?.now ?: LocalDateTime.now(CLUJ_ZONE)
    val outgoing = direction == "0"
    val isFavorite = favoriteRoutes.any { it.routeId == vm.routeId && it.direction == direction }
    val openOnPhone = rememberOpenOnPhone(Links.route(vm.routeId, direction))

    // Opened from a stop, the times are when the bus gets there. After a swap that is the
    // stop of the same name on the other side, if this direction has one.
    val allStops by Services.repository.stops.collectAsStateWithLifecycle()
    val names = remember(allStops) { allStops.associate { it.stopId to it.name } }
    val atStop = remember(info, direction, names) {
        val id = vm.fromStopId ?: return@remember null
        val stops = info?.let { stopsForDirection(it, tripIdFor(vm.routeId, direction)) }.orEmpty()
        val stop = stops.find { it.stop.stopId == id }
            ?: names[id]?.let { name -> stops.find { names[it.stop.stopId] == name } }
        stop?.let { found -> names[found.stop.stopId]?.let { found.offsetFromStart to it } }
    }
    val offset = atStop?.first ?: 0

    val schedule = info?.let { daySchedule(it.timetable, now) }
    val frequency = if (outgoing) schedule?.inFrequency else schedule?.outFrequency
    val departures = remember(schedule, outgoing, offset) {
        schedule?.let { departureMinutes(it, outgoing).map { minutes -> minutes + offset }.sorted() }.orEmpty()
    }
    val nowMinutes = minutesOfDay(now)
    // From the current hour on: only what is still to come matters here.
    val hours = remember(departures, nowMinutes) {
        departures.filter { it / 60 >= nowMinutes / 60 }
            .groupBy { it / 60 }
            .map { (hour, list) -> HourRow("%02d".format(hour % 24), list.map { "%02d".format(it % 60) to (it < nowMinutes) }) }
    }
    val frequencyLabel = frequency?.let {
        if (it.minMinutes != 0 && it.maxMinutes != 0 && it.minMinutes != it.maxMinutes) {
            stringResource(R.string.frequency_every_range, it.minMinutes, it.maxMinutes)
        } else {
            stringResource(R.string.frequency_every, if (it.minMinutes != 0) it.minMinutes else it.maxMinutes)
        }
    }

    ListScreen { spec ->
        item {
            val heading = atStop?.second ?: terminal.takeIf { it.isNotEmpty() }?.let { "→\u00A0$it" }
            LineHeader(spec, info?.shortName, info?.color, heading.orEmpty())
        }
        // The times are what this screen is for, so its actions stay small.
        item {
            Row(contentModifier(spec), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                IconToggleButton(
                    checked = isFavorite,
                    onCheckedChange = { Services.favorites.toggleRoute(vm.routeId, direction) },
                    modifier = Modifier.size(SMALL_ACTION),
                ) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(R.string.favorite_line),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (canSwap) {
                    FilledTonalIconButton(onClick = vm::swap, modifier = Modifier.size(SMALL_ACTION)) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.switch_direction), modifier = Modifier.size(18.dp))
                    }
                }
                FilledTonalIconButton(onClick = onMap, modifier = Modifier.size(SMALL_ACTION)) {
                    Icon(Icons.Rounded.Map, contentDescription = stringResource(R.string.map), modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(onClick = openOnPhone, modifier = Modifier.size(SMALL_ACTION)) {
                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = stringResource(R.string.open_on_phone), modifier = Modifier.size(18.dp))
                }
            }
        }
        when {
            info == null -> item { Loading(spec) }
            frequencyLabel != null -> item { Message(frequencyLabel, spec) }
            departures.isEmpty() -> item { Message(stringResource(R.string.no_schedule), spec) }
            hours.isEmpty() -> item { Message(stringResource(R.string.no_more_today), spec) }
        }
        if (info != null) {
            items(hours.size, key = { hours[it].hour }) { i ->
                val row = hours[i]
                Row(
                    contentModifier(spec).padding(horizontal = 12.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(row.hour, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((minute, past) in row.minutes) {
                            Text(
                                minute,
                                style = MaterialTheme.typography.titleLarge,
                                color = if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
