package online.bmarian.bus.presentation.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.daySchedule
import online.bmarian.bus.logic.departureMinutes
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.logic.minutesOfDay
import online.bmarian.bus.presentation.FavoriteToggle
import online.bmarian.bus.presentation.FloatButton
import online.bmarian.bus.presentation.FloatIconButton
import online.bmarian.bus.presentation.Heading
import online.bmarian.bus.presentation.Icons
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Note
import online.bmarian.bus.presentation.RouteBadge
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLabel
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.routeColor
import online.bmarian.bus.presentation.transformed
import java.time.LocalDateTime

// Opened from a stop, the map starts on that stop.
private const val FROM_STOP_ZOOM = 15f

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
    val other = if (direction == "0") "1" else "0"
    val canSwap = info != null && vm.hasDirection(other)

    Box(Modifier.fillMaxSize()) {
        if (timetableOpen) {
            TimetableView(vm, info, direction, terminal)
        } else {
            RouteMapView(vm, camera, info, terminal, failed)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSwap) FloatIconButton(Icons.Swap, stringResource(R.string.switch_direction), onClick = vm::swap)
            FloatButton(
                stringResource(if (timetableOpen) R.string.map else R.string.timetable),
                onClick = { timetableOpen = !timetableOpen },
            )
        }
    }
}

@Composable
private fun RouteMapView(
    vm: RouteViewModel,
    camera: online.bmarian.bus.presentation.map.MapCamera,
    info: ShapeInfo?,
    terminal: String,
    failed: Boolean,
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
            )
            if (info != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 30.dp)
                        .widthIn(max = 150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xE60F172A))
                        .padding(start = 3.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    RouteBadge(info.shortName, info.color, small = true)
                    if (terminal.isNotEmpty()) {
                        Text("→ $terminal", color = Palette.Title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text(
                    note,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xE60F172A))
                        .clickable(enabled = failed, onClick = vm::load)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Palette.Action,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private class HourRow(val hour: String, val minutes: List<Pair<String, Boolean>>)

@Composable
private fun TimetableView(vm: RouteViewModel, info: ShapeInfo?, direction: String, terminal: String) {
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now = ui?.now ?: LocalDateTime.now(CLUJ_ZONE)
    val outgoing = direction == "0"
    val isFavorite = favoriteRoutes.any { it.routeId == vm.routeId && it.direction == direction }

    val schedule = info?.let { daySchedule(it.timetable, now) }
    val frequency = if (outgoing) schedule?.inFrequency else schedule?.outFrequency
    val departures = remember(schedule, outgoing) { schedule?.let { departureMinutes(it, outgoing).sorted() }.orEmpty() }
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

    ListScreen(bottomInset = 48.dp) { spec ->
        item {
            Heading(
                title = if (terminal.isNotEmpty()) "→ $terminal" else "",
                modifier = transformed(spec),
                badge = info?.let { { RouteBadge(it.shortName, it.color) } },
            )
        }
        item {
            FavoriteToggle(
                isFavorite = isFavorite,
                onToggle = { Services.favorites.toggleRoute(vm.routeId, direction) },
                addLabel = stringResource(R.string.add_to_favorites),
                removeLabel = stringResource(R.string.remove_from_favorites),
                modifier = transformed(spec),
            )
        }
        when {
            info == null -> item { Note(stringResource(R.string.loading_route), transformed(spec)) }
            frequencyLabel != null -> item { Note(frequencyLabel, transformed(spec)) }
            departures.isEmpty() -> item { Note(stringResource(R.string.no_schedule), transformed(spec)) }
            hours.isEmpty() -> item { Note(stringResource(R.string.no_more_today), transformed(spec)) }
        }
        if (info != null) {
            items(hours.size, key = { hours[it].hour }) { i ->
                val row = hours[i]
                Row(
                    modifier = transformed(spec).fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(row.hour, color = Palette.Title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((minute, past) in row.minutes) {
                            Text(minute, color = if (past) Palette.Faint else Palette.Action, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
