package online.bmarian.bus.presentation.route

import online.bmarian.bus.presentation.map.stationMapStop
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
import androidx.compose.material.icons.rounded.NotificationsActive
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import online.bmarian.bus.R
import online.bmarian.bus.data.Cities
import kotlin.math.roundToInt
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.Services
import online.bmarian.bus.data.ShapeInfo
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.data.terminalName
import online.bmarian.bus.data.tripIdFor
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.ScheduleDays
import online.bmarian.bus.logic.daySchedule
import online.bmarian.bus.logic.departureMinutes
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.logic.minutesOfDay
import online.bmarian.bus.logic.specialDayOn
import online.bmarian.bus.logic.stopsForDirection
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.LineHeader
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.LABELLED_WEIGHT
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.RemindTarget
import online.bmarian.bus.presentation.ToggleAction
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.Rolling
import online.bmarian.bus.presentation.map.ChipText
import online.bmarian.bus.presentation.map.MAP_CHIP_TOP
import online.bmarian.bus.presentation.map.MapCamera
import online.bmarian.bus.presentation.map.MapChip
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLabel
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.MapControls
import online.bmarian.bus.presentation.map.rememberMapChrome
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.rememberRemind
import online.bmarian.bus.presentation.reminding
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.lineColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.ListHeader
import online.bmarian.bus.logic.formatMeters
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.LabelAction
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface
import online.bmarian.bus.presentation.stop.laterTimes
import java.time.LocalDateTime

private const val FROM_STOP_ZOOM = 13f
private const val ME_ZOOM = 14f
private val MAP_BUTTON = 44.dp

@Composable
fun routeViewModel(routeId: Int, direction: String, fromStopId: Int?): RouteViewModel {
    val context = LocalContext.current
    return viewModel(key = "route-$routeId-${fromStopId ?: ""}") {
        RouteViewModel(routeId, direction, fromStopId).apply { UserLocation.saved(context)?.let(::locate) }
    }
}

enum class RouteView { Summary, Map, Timetable }

@Composable
fun RouteScreen(vm: RouteViewModel, start: RouteView = RouteView.Summary, onOpenDeparture: (Int, String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }

    val info by vm.shapeInfo.collectAsStateWithLifecycle()
    val direction by vm.direction.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(start) }
    val camera = rememberMapCamera()

    BackHandler(enabled = view != start) { view = start }

    val terminal = info?.let { terminalName(it.longName, direction) }.orEmpty()
    val canSwap = info != null && vm.hasDirection(if (direction == "0") "1" else "0")

    when (view) {
        RouteView.Summary -> LineSummary(
            vm, info, terminal, failed, canSwap,
            onMap = { view = RouteView.Map },
            onTimetable = { view = RouteView.Timetable },
            onOpenDeparture = onOpenDeparture,
        )
        RouteView.Map -> RouteMapView(vm, camera, info, terminal, failed, canSwap, onTimetable = { view = RouteView.Timetable })
        RouteView.Timetable -> TimetableView(vm, info, direction, terminal, canSwap, onMap = { view = RouteView.Map })
    }
}

@Composable
private fun LineSummary(
    vm: RouteViewModel,
    info: ShapeInfo?,
    terminal: String,
    failed: Boolean,
    canSwap: Boolean,
    onMap: () -> Unit,
    onTimetable: () -> Unit,
    onOpenDeparture: (Int, String) -> Unit,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val me by rememberUserLocation()
    LaunchedEffect(me) { me?.let(vm::locate) }
    val fix = me ?: remember { UserLocation.recent(context) }
    val allStops by Services.repository.stops.collectAsStateWithLifecycle()
    val names = remember(allStops) { allStops.associate { it.stopId to it.name } }
    val direction by vm.direction.collectAsStateWithLifecycle()

    val current = ui
    val stops = current?.stops.orEmpty()
    val at = remember(stops, fix, names) {
        val from = vm.fromStopId
        val opened = from?.let { id -> stops.indexOfFirst { it.stop.stopId == id }.takeIf { it >= 0 } ?: names[id]?.let { name -> stops.indexOfFirst { names[it.stop.stopId] == name } }?.takeIf { it >= 0 } }
        opened ?: fix?.let { f ->
            stops.indices.filter { stops[it].stop.lat != 0.0 }.minByOrNull { haversineMeters(f.lat, f.lon, stops[it].stop.lat, stops[it].stop.lon) }
        } ?: stops.indices.firstOrNull()
    }
    val stop = at?.let { stops[it].stop }
    val arrivals = at?.let { current?.arrivals?.getOrNull(it) }.orEmpty()

    ListScreen { spec ->
        item {
            ListHeader(modifier = itemModifier(spec), transformation = surface(spec)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    info?.let { LineBadge(it.shortName, it.color, height = 30.dp) }
                    if (terminal.isNotEmpty()) Rolling(terminal) { Text("→ $it", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        when {
            failed -> item { Message(stringResource(R.string.load_failed), spec) }
            current == null -> item { Loading(spec) }
            stop == null -> item { Message(stringResource(R.string.no_schedule), spec) }
            else -> {
                item {
                    val meters = fix?.takeIf { stop.lat != 0.0 }?.let { haversineMeters(it.lat, it.lon, stop.lat, stop.lon) }
                    Text(
                        stringResource(R.string.at_stop, names[stop.stopId] ?: stop.headsign) + meters?.let { " · " + formatMeters(it) }.orEmpty(),
                        contentModifier(spec).padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item {
                    val press = remember { MutableInteractionSource() }
                    val first = arrivals.firstOrNull()
                    Card(
                        onClick = { onOpenDeparture(stop.stopId, tripIdFor(vm.routeId, direction)) },
                        modifier = itemModifier(spec).squish(press),
                        interactionSource = press,
                        transformation = surface(spec),
                        enabled = first != null,
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (first == null) {
                                Text(stringResource(R.string.no_more_today), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            } else {
                                Eta(first.minutes, first.isLive, current.now, style = MaterialTheme.typography.numeralSmall, iconSize = 16.dp, idle = MaterialTheme.colorScheme.onSurface)
                                laterTimes(arrivals.drop(1).map { it.minutes }, current.now)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            ActionRow(spec) {
                LabelAction(Icons.Rounded.Map, stringResource(R.string.map), onMap)
                if (canSwap) PillAction(Icons.Rounded.SwapVert, stringResource(R.string.switch_direction), vm::swap)
            }
        }
        item {
            Box(contentModifier(spec), contentAlignment = Alignment.Center) {
                CompactButton(
                    onClick = onTimetable,
                    icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                    label = { Text(stringResource(R.string.line_timetable)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                )
            }
        }
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
    LaunchedEffect(me) { me?.let(vm::locate) }
    val favoriteStops by Services.favorites.stops.collectAsStateWithLifecycle()
    val nowLabel = stringResource(R.string.now)
    val soonLabel = stringResource(R.string.soon)
    val color = lineColor(info?.shortName, info?.color ?: "")
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
            when {
                id == fromStopId -> stationMapStop(id, it.stop.lat, it.stop.lon, selected = true)
                id in favoriteStops -> MapStop("$id", it.stop.lat, it.stop.lon, Palette.Heart, heart = true)
                else -> stationMapStop(id, it.stop.lat, it.stop.lon)
            }
        }
    }

    // The other direction re-fits before its line draws.
    val shape = current?.shape
    var framedShape by remember { mutableStateOf(shape) }
    LaunchedEffect(shape) {
        if (shape != null && framedShape != null && shape !== framedShape) camera.framed = false
        if (shape != null) framedShape = shape
    }

    val chrome = rememberMapChrome()
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                frame = frame,
                onMapTap = chrome::toggle,
                lines = current?.shape?.let { listOf(MapLine(it.shape.map { p -> p.lat to p.lon }, color)) }.orEmpty(),
                arrows = true,
                stops = mapStops,
                labels = labels,
                vehicles = current?.roundTripVehicles.orEmpty().map {
                    MapVehicle("${it.id}", it.latitude, it.longitude, info?.shortName.orEmpty(), color, it.heading, faded = true, vehicle = it)
                } + current?.vehicles.orEmpty().map {
                    MapVehicle("${it.id}", it.latitude, it.longitude, info?.shortName.orEmpty(), color, it.heading, vehicle = it)
                },
                me = me,
                ambient = LocalAmbient.current,
            )
            if (info != null) {
                MapControls(chrome, Modifier.align(Alignment.TopCenter).padding(top = MAP_CHIP_TOP)) {
                    MapChip {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            LineBadge(info.shortName, info.color, height = 20.dp)
                            if (terminal.isNotEmpty()) Rolling(terminal) { ChipText("→ $it") }
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
                MapChip(Modifier.align(Alignment.Center)) { ChipText(note) }
            }
            if (!LocalAmbient.current) MapControls(chrome, Modifier.align(Alignment.BottomCenter)) {
                Row(
                    modifier = Modifier.padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    me?.let { fix ->
                        FilledTonalIconButton(
                            onClick = { camera.centerOn(fix.lat, fix.lon, ME_ZOOM) },
                            modifier = Modifier.size(MAP_BUTTON),
                            shapes = IconButtonDefaults.animatedShapes(),
                        ) { Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.my_location)) }
                    }
                    FilledIconButton(onClick = onTimetable, modifier = Modifier.size(MAP_BUTTON), shapes = IconButtonDefaults.animatedShapes()) {
                        Icon(Icons.Rounded.Schedule, contentDescription = stringResource(R.string.timetable))
                    }
                    if (canSwap) {
                        FilledTonalIconButton(onClick = vm::swap, modifier = Modifier.size(MAP_BUTTON), shapes = IconButtonDefaults.animatedShapes()) {
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

    val allStops by Services.repository.stops.collectAsStateWithLifecycle()
    val names = remember(allStops) { allStops.associate { it.stopId to it.name } }
    val atStop = remember(info, direction, names) {
        val id = vm.fromStopId ?: return@remember null
        val stops = info?.let { stopsForDirection(it, tripIdFor(vm.routeId, direction)) }.orEmpty()
        val stop = stops.find { it.stop.stopId == id }
            ?: names[id]?.let { name -> stops.find { names[it.stop.stopId] == name } }
        stop?.let { found -> names[found.stop.stopId]?.let { found to it } }
    }
    val offset = atStop?.first?.let { (it.secondsFromStart / 60).roundToInt() } ?: 0

    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val tripId = tripIdFor(vm.routeId, direction)
    val reminded = atStop?.let { (stop, _) -> reminding(follows, reminders, listOf(stop.stop.stopId), vm.routeId, tripId) }
    val remind = rememberRemind()

    val calendar by ScheduleDays.state.collectAsStateWithLifecycle()
    val special = specialDayOn(now.toLocalDate(), calendar)
    val schedule = info?.let { daySchedule(it.timetable, now) }
    val frequency = if (outgoing) schedule?.inFrequency else schedule?.outFrequency
    val departures = remember(schedule, outgoing, offset) {
        schedule?.let { departureMinutes(it, outgoing).map { minutes -> minutes + offset }.sorted() }.orEmpty()
    }
    val nowMinutes = minutesOfDay(now)
    fun target(): RemindTarget? {
        val (stop, name) = atStop ?: return null
        val route = info ?: return null
        val followed = Followed(stop.stop.stopId, name, vm.routeId, tripId, route.shortName, route.color, terminal, Cities.current.value)
        val minutes = departures.firstOrNull { it >= nowMinutes }?.minus(nowMinutes) ?: return RemindTarget(followed)
        return RemindTarget(followed, stop.stop.lat, stop.stop.lon, minutes)
    }
    val hours = remember(departures, nowMinutes) {
        departures.filter { it / 60 >= nowMinutes / 60 }
            .groupBy { it / 60 }
            .map { (hour, list) -> HourRow("%02d".format(hour % 24), list.map { "%02d".format(it % 60) to (it < nowMinutes) }) }
    }
    val frequencyLabel = frequency?.let {
        if (it.minMinutes != 0 && it.maxMinutes != 0 && it.minMinutes != it.maxMinutes) {
            pluralStringResource(R.plurals.frequency_every_range, it.maxMinutes, it.minMinutes, it.maxMinutes)
        } else {
            val minutes = if (it.minMinutes != 0) it.minMinutes else it.maxMinutes
            pluralStringResource(R.plurals.frequency_every, minutes, minutes)
        }
    }

    ListScreen { spec ->
        item { LineHeader(spec, info?.shortName, info?.color, terminal.takeIf { it.isNotEmpty() }?.let { "→ $it" }.orEmpty()) }
        if (atStop != null) item { Message(stringResource(R.string.from_stop, atStop.second), spec) }
        item {
            ActionRow(spec) {
                if (reminded != null) {
                    ToggleAction(
                        Icons.Rounded.NotificationsActive,
                        stringResource(R.string.remind_set),
                        reminded.on,
                        { remind(reminded, ::target) },
                        weight = LABELLED_WEIGHT,
                        checkedLabel = stringResource(if (reminded.reminder != null) R.string.reminder else R.string.following),
                    )
                }
                ToggleAction(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(R.string.save),
                    isFavorite,
                    { Services.favorites.toggleRoute(vm.routeId, direction) },
                    color = Palette.Heart,
                    onColor = Color.White,
                    iconOnly = reminded != null,
                    checkedLabel = stringResource(R.string.saved),
                )
            }
        }
        item {
            ActionRow(spec) {
                if (canSwap) PillAction(Icons.Rounded.SwapVert, stringResource(R.string.switch_direction), vm::swap)
                PillAction(Icons.Rounded.Map, stringResource(R.string.map), onMap)
            }
        }
        if (special != null) {
            item {
                val timetable = stringResource(
                    when (special.schedule) {
                        "saturday" -> R.string.timetable_name_saturday
                        "sunday" -> R.string.timetable_name_sunday
                        else -> R.string.timetable_name_weekday
                    },
                )
                Message(stringResource(R.string.special_day_short, timetable), spec)
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
