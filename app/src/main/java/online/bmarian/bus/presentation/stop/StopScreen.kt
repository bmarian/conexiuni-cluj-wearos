package online.bmarian.bus.presentation.stop

import online.bmarian.bus.data.StationLook
import online.bmarian.bus.logic.Mode
import online.bmarian.bus.presentation.StationIcon
import online.bmarian.bus.presentation.map.stationMapStop
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.directionFromTripId
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.DueArrivals
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.RemindTarget
import online.bmarian.bus.presentation.Ticker
import online.bmarian.bus.presentation.ToggleAction
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.LineHeader
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.LocalLiveBeat
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.cascade
import online.bmarian.bus.presentation.etaText
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.map.ChipText
import online.bmarian.bus.presentation.map.MAP_CHIP_TOP
import online.bmarian.bus.presentation.map.MapChip
import online.bmarian.bus.presentation.map.MapArrival
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.MapControls
import online.bmarian.bus.presentation.map.rememberMapChrome
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.rememberClock
import online.bmarian.bus.presentation.rememberOpenOnPhone
import online.bmarian.bus.presentation.rememberRemind
import online.bmarian.bus.presentation.reminding
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.LocalLineLooks
import online.bmarian.bus.presentation.theme.lineColor
import online.bmarian.bus.presentation.theme.manyLines
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.logic.formatMeters
import online.bmarian.bus.presentation.LabelAction
import online.bmarian.bus.presentation.PopIcon
import java.time.LocalDateTime

private const val STOP_MAP_ZOOM = 13f
private const val ME_ZOOM = 14f

@Composable
fun stopViewModel(stopId: Int): StopViewModel = viewModel(key = "stop-$stopId") { StopViewModel(stopId) }

@Composable
fun LiveWhileVisible(vm: StopViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
}

fun StopDeparture.destination() = longName.substringAfterLast(" - ")

@Composable
fun laterTimes(later: List<Int>, now: LocalDateTime): String? {
    if (later.isEmpty()) return null
    return stringResource(R.string.then_times, later.map { etaText(it, now) }.joinToString(" · "))
}

@Composable
fun StopScreen(
    vm: StopViewModel,
    onOpenDeparture: (StopDeparture) -> Unit,
    onOpenMap: () -> Unit,
) {
    LiveWhileVisible(vm)
    val context = LocalContext.current
    val name by vm.name.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favoriteStops by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val openOnPhone = rememberOpenOnPhone(Links.stop(vm.stopId))
    val infos by vm.infos.collectAsStateWithLifecycle()
    val index by Services.repository.network.index.collectAsStateWithLifecycle()
    val look = remember(index, vm.stopId) { index?.look(vm.stopId) }
    val platforms = infos.map { it.stopId }.ifEmpty { listOf(vm.stopId) }
    val favoritePlatform = platforms.firstOrNull { it in favoriteStops }
    val fix = remember { UserLocation.recent(context) }
    val meters = location?.let { at -> fix?.let { haversineMeters(it.lat, it.lon, at.first, at.second) } }

    val sorted = remember(ui, favoriteRoutes) {
        ui?.departures?.sortedWith(compareBy({ if (vm.isDepartureFavorite(it)) 0 else 1 }, { it.minutesLeft }))
    }
    val beat by vm.beat.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalLiveBeat provides beat) {
    ListScreen { spec ->
        item { StationKind(look, meters, spec) }
        item { Title(name ?: "…", spec) }
        when {
            failed -> {
                item { Message(stringResource(R.string.load_failed), spec) }
                item {
                    val press = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = vm::load,
                        modifier = itemModifier(spec).squish(press),
                        interactionSource = press,
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        label = { Text(stringResource(R.string.retry)) },
                    )
                }
            }
            sorted == null -> item { Loading(spec) }
            sorted.isEmpty() -> item { Message(stringResource(R.string.no_schedule), spec) }
            else -> items(sorted.size, key = { sorted[it].tripId }) { i ->
                val departure = sorted[i]
                val now = ui!!.now
                val reminded = reminding(follows, reminders, platforms, departure.routeId, departure.tripId).on
                val press = remember { MutableInteractionSource() }
                TitleCard(
                    onClick = { onOpenDeparture(departure) },
                    modifier = itemModifier(spec).squish(press).cascade(i, "list"),
                    interactionSource = press,
                    transformation = surface(spec),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LineBadge(departure.shortName, departure.color, height = 22.dp)
                            if (reminded) Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    time = { Eta(departure.nextTimes.firstOrNull()?.minutes ?: departure.minutesLeft, !departure.isStatic, now, mode = look?.mode ?: Mode.BUS) },
                ) {
                    Column {
                        Text(departure.destination(), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        laterTimes(departure.nextTimes.drop(1).map { it.minutes }, now)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            val favorite = favoritePlatform != null
            ActionRow(spec) {
                ToggleAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (favorite) R.string.saved else R.string.save),
                    favorite,
                    { Services.favorites.toggleStop(favoritePlatform ?: vm.stopId) },
                    color = Palette.Heart,
                    onColor = Color.White,
                    iconOnly = true,
                )
                PillAction(Icons.Rounded.Map, stringResource(R.string.open_map), onOpenMap)
                PillAction(Icons.Rounded.PhoneAndroid, stringResource(R.string.open_on_phone), openOnPhone)
            }
        }
    }
    }
}

// While followed, the follow's own reading is shown, so the screen says the same as its card.
@Composable
fun DepartureScreen(
    vm: StopViewModel,
    routeId: Int,
    tripId: String,
    onOpenMap: () -> Unit,
    onOpenLine: () -> Unit,
    onOpenFollow: (Followed) -> Unit,
) {
    LiveWhileVisible(vm)
    val name by vm.name.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val infos by vm.infos.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val departure = ui?.departures?.firstOrNull { it.routeId == routeId && it.tripId == tripId }
    val direction = directionFromTripId(tripId)
    val platforms = infos.map { it.stopId }.ifEmpty { listOf(vm.stopId) }
    val reminded = reminding(follows, reminders, platforms, routeId, tripId)
    val follow = reminded.follow
    val remind = rememberRemind(onSet = onOpenFollow)
    val nowMs by rememberClock()
    val beat by vm.beat.collectAsStateWithLifecycle()
    val index by Services.repository.network.index.collectAsStateWithLifecycle()
    val mode = remember(index, vm.stopId) { index?.look(vm.stopId)?.mode } ?: Mode.BUS

    fun target(): RemindTarget? {
        val d = departure ?: return null
        val platform = vm.platformOf(d)
        val followed = Followed(platform, name.orEmpty(), routeId, tripId, d.shortName, d.color, d.destination(), Cities.current.value)
        val stop = vm.platform(platform) ?: Services.repository.cachedStopInfo(platform) ?: return RemindTarget(followed)
        return RemindTarget(followed, stop.lat, stop.lon, d.nextTimes.firstOrNull()?.minutes ?: d.minutesLeft)
    }

    CompositionLocalProvider(LocalLiveBeat provides beat) {
    ListScreen { spec ->
        val current = departure
        val shortName = current?.shortName ?: follow?.followed?.shortName
        val color = current?.color ?: follow?.followed?.color
        item {
            LineHeader(spec, shortName, color, current?.destination() ?: follow?.followed?.destination ?: (name ?: "…"))
        }
        if (current == null && follow == null) {
            item { if (ui == null) Loading(spec) else Message(stringResource(R.string.no_departures), spec) }
            return@ListScreen
        }
        val now = ui?.now ?: LocalDateTime.now(CLUJ_ZONE)
        val own = current?.let { it.nextTimes.firstOrNull()?.minutes ?: it.minutesLeft }
        val minutes = follow?.minutesAt(nowMs) ?: own
        val live = if (follow?.minutes != null) follow.live else current?.isStatic == false
        val later = follow?.later?.takeIf { follow.minutes != null } ?: current?.nextTimes?.drop(1)?.map { it.minutes }.orEmpty()
        item {
            Column(contentModifier(spec), horizontalAlignment = Alignment.CenterHorizontally) {
                if (minutes == null) Ticker("…", style = MaterialTheme.typography.numeralMedium, color = MaterialTheme.colorScheme.onSurface)
                else Eta(minutes, live, now, style = MaterialTheme.typography.numeralMedium, mode = mode, iconSize = 18.dp, idle = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.status_from, stringResource(if (live) R.string.live else R.string.scheduled), name ?: follow?.followed?.stopName.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            val press = remember { MutableInteractionSource() }
            val set = reminded.follow?.followed ?: reminded.reminder?.followed
            Button(
                onClick = { if (set != null) onOpenFollow(set) else remind(reminded, ::target) },
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                colors = if (set != null) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                icon = { PopIcon(Icons.Rounded.NotificationsActive, set != null, Modifier.size(20.dp)) },
                label = {
                    Text(
                        stringResource(
                            when {
                                reminded.follow != null -> R.string.following
                                reminded.reminder != null -> R.string.reminder
                                else -> R.string.remind_set
                            },
                        ),
                    )
                },
            )
        }
        item {
            val favorite = favoriteRoutes.any { it.routeId == routeId && it.direction == direction }
            ActionRow(spec) {
                LabelAction(Icons.Rounded.Map, stringResource(R.string.map), onOpenMap)
                ToggleAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (favorite) R.string.saved else R.string.save),
                    favorite,
                    { Services.favorites.toggleRoute(routeId, direction) },
                    color = Palette.Heart,
                    onColor = Color.White,
                    iconOnly = true,
                )
            }
        }
        if (later.isNotEmpty()) {
            item {
                Text(
                    laterTimes(later, now).orEmpty(),
                    contentModifier(spec).padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (shortName != null) {
            item {
                Box(contentModifier(spec), contentAlignment = Alignment.Center) {
                    CompactButton(
                        onClick = onOpenLine,
                        label = { Text(stringResource(R.string.whole_line, shortName)) },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.StationKind(look: StationLook?, meters: Double?, spec: androidx.wear.compose.material3.lazy.TransformationSpec) {
    Row(
        contentModifier(spec),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationIcon(look, size = 20.dp)
        Text(
            stringResource(stopKind(look?.mode ?: Mode.BUS)) + meters?.let { " · " + formatMeters(it) }.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun stopKind(mode: Mode) = when (mode) {
    Mode.METRO -> R.string.stop_kind_metro
    Mode.RAIL -> R.string.stop_kind_rail
    Mode.TRAM -> R.string.stop_kind_tram
    Mode.TROLLEYBUS -> R.string.stop_kind_trolleybus
    Mode.BUS -> R.string.stop_kind_bus
}

@Composable
fun StopMapScreen(vm: StopViewModel) {
    LiveWhileVisible(vm)
    val name by vm.name.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val layer by vm.mapLayer.collectAsStateWithLifecycle()
    val apart = manyLines(layer.lines.map { it.name })
    val me by rememberUserLocation()
    val camera = rememberMapCamera()
    val frame = location?.let { MapFrame.Focus(it.first, it.second, STOP_MAP_ZOOM) }
    val chrome = rememberMapChrome()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dueArrivals = remember { DueArrivals() }
    var arrival by remember { mutableStateOf<MapArrival?>(null) }
    val looks = LocalLineLooks.current
    LaunchedEffect(ui) {
        val departures = ui?.departures ?: return@LaunchedEffect
        val at = location ?: return@LaunchedEffect
        for (departure in dueArrivals.newlyDue(departures).filter(vm::isDepartureFavorite)) {
            val bus = layer.vehicles.filter { it.vehicle.tripId == departure.tripId }.minByOrNull { haversineMeters(it.lat, it.lon, at.first, at.second) }
            arrival = MapArrival(at.first, at.second, looks.color(departure.shortName, departure.color, apart), bus?.id, (arrival?.seq ?: 0) + 1)
        }
    }
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                frame = frame,
                onMapTap = chrome::toggle,
                lines = layer.lines.map { MapLine(it.points, lineColor(it.name, it.color, apart), name = it.name) },
                terminals = true,
                stops = listOfNotNull(location?.let { stationMapStop(vm.stopId, it.first, it.second, selected = true) }),
                vehicles = layer.vehicles.map { MapVehicle(it.id, it.lat, it.lon, it.name, lineColor(it.name, it.color, apart), it.heading, vehicle = it.vehicle) },
                me = me,
                ambient = LocalAmbient.current,
                arrival = arrival,
            )
            name?.let {
                MapControls(chrome, Modifier.align(Alignment.TopCenter).padding(top = MAP_CHIP_TOP)) {
                    MapChip { ChipText(it) }
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
