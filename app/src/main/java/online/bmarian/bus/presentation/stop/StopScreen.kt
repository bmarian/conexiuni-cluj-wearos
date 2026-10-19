package online.bmarian.bus.presentation.stop

import online.bmarian.bus.data.StationLook
import online.bmarian.bus.logic.Mode
import online.bmarian.bus.presentation.StationIcon
import online.bmarian.bus.presentation.map.stationMapStop
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.directionFromTripId
import kotlinx.coroutines.launch
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.LABELLED_WEIGHT
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.Ticker
import online.bmarian.bus.presentation.ToggleAction
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.LineHeader
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.RealtimeIcon
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.cascade
import online.bmarian.bus.presentation.etaText
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.MapControls
import online.bmarian.bus.presentation.map.rememberMapChrome
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.rememberOpenOnPhone
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.routeColor
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
private fun laterTimes(departure: StopDeparture, now: LocalDateTime): String? {
    val later = departure.nextTimes.drop(1)
    if (later.isEmpty()) return null
    return stringResource(R.string.then_times, later.map { etaText(it.minutes, now) }.joinToString(" · "))
}

@Composable
fun StopScreen(
    vm: StopViewModel,
    onOpenDeparture: (StopDeparture) -> Unit,
    onOpenMap: () -> Unit,
) {
    LiveWhileVisible(vm)
    val name by vm.name.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favoriteStops by Services.favorites.stops.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val openOnPhone = rememberOpenOnPhone(Links.stop(vm.stopId))
    val infos by vm.infos.collectAsStateWithLifecycle()
    val index by Services.repository.network.index.collectAsStateWithLifecycle()
    val look = remember(index, vm.stopId) { index?.look(vm.stopId) }
    // A station is a favourite when any of its platforms is.
    val favoritePlatform = (infos.map { it.stopId }.ifEmpty { listOf(vm.stopId) }).firstOrNull { it in favoriteStops }

    val sorted = remember(ui, favoriteRoutes) {
        ui?.departures?.sortedWith(compareBy({ if (vm.isDepartureFavorite(it)) 0 else 1 }, { it.minutesLeft }))
    }

    ListScreen { spec ->
        item { StationKind(look, spec) }
        item { Title(name ?: "…", spec) }
        item {
            val favorite = favoritePlatform != null
            ActionRow(spec) {
                ToggleAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(R.string.save),
                    favorite,
                    { Services.favorites.toggleStop(favoritePlatform ?: vm.stopId) },
                    color = Palette.Heart,
                    onColor = Color.White,
                    weight = LABELLED_WEIGHT,
                )
                PillAction(Icons.Rounded.Map, stringResource(R.string.open_map), onOpenMap)
                PillAction(Icons.Rounded.PhoneAndroid, stringResource(R.string.open_on_phone), openOnPhone)
            }
        }
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
                val followed = follows.any { it.matches(vm.platformOf(departure), departure.routeId, departure.tripId) }
                val press = remember { MutableInteractionSource() }
                TitleCard(
                    onClick = { onOpenDeparture(departure) },
                    modifier = itemModifier(spec).squish(press).cascade(i, "list"),
                    interactionSource = press,
                    transformation = surface(spec),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LineBadge(departure.shortName, departure.color, height = 22.dp)
                            if (followed) Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    time = { Eta(departure.nextTimes.firstOrNull()?.minutes ?: departure.minutesLeft, !departure.isStatic, now) },
                ) {
                    Column {
                        Text(departure.destination(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        laterTimes(departure, now)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
fun DepartureScreen(vm: StopViewModel, routeId: Int, tripId: String, onOpenRoute: () -> Unit) {
    LiveWhileVisible(vm)
    val context = LocalContext.current
    val name by vm.name.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val departure = ui?.departures?.firstOrNull { it.routeId == routeId && it.tripId == tripId }
    val direction = directionFromTripId(tripId)
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val platform = departure?.let(vm::platformOf) ?: vm.stopId
    val following = follows.firstOrNull { it.matches(platform, routeId, tripId) }
    val reminder = reminders.firstOrNull { it.followed.city == Cities.current.value && it.followed.stopId == platform && it.followed.routeId == routeId && it.followed.tripId == tripId }
    val isFollowing = following != null || reminder != null
    val openOnPhone = rememberOpenOnPhone(Links.stop(vm.stopId))

    fun follow() {
        val d = departure ?: return
        val followed = Followed(platform, name.orEmpty(), routeId, tripId, d.shortName, d.color, d.destination(), Cities.current.value)
        val stop = vm.platform(platform) ?: Services.repository.cachedStopInfo(platform) ?: return FollowManager.start(context, followed)
        val minutes = d.nextTimes.firstOrNull()?.minutes ?: d.minutesLeft
        val app = context.applicationContext
        Services.scope.launch { Reminders.remind(app, followed, stop.lat, stop.lon, minutes) }
    }
    // POST_NOTIFICATIONS needs a runtime grant on Wear OS 4+.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { follow() }

    val onFollow = {
        when {
            following != null || reminder != null -> {
                if (following != null) FollowManager.stop(context, following.id)
                if (reminder != null) Reminders.remove(context, reminder.id)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> follow()
        }
    }

    ListScreen { spec ->
        val current = departure
        item {
            LineHeader(spec, current?.shortName, current?.color, current?.destination() ?: (name ?: "…"))
        }
        if (current == null) {
            item { if (ui == null) Loading(spec) else Message(stringResource(R.string.no_departures), spec) }
            return@ListScreen
        }
        val now = ui!!.now
        val minutes = current.nextTimes.firstOrNull()?.minutes ?: current.minutesLeft
        val live = !current.isStatic
        item {
            Column(contentModifier(spec), horizontalAlignment = Alignment.CenterHorizontally) {
                val color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (live) RealtimeIcon(color, size = 16.dp)
                    Ticker(etaText(minutes, now), style = MaterialTheme.typography.numeralSmall, color = color)
                }
                Text(
                    stringResource(if (live) R.string.live else R.string.scheduled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            val favorite = favoriteRoutes.any { it.routeId == routeId && it.direction == direction }
            ActionRow(spec) {
                ToggleAction(Icons.Rounded.NotificationsActive, stringResource(R.string.remind_set), isFollowing, onFollow, weight = LABELLED_WEIGHT)
                ToggleAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(R.string.save),
                    favorite,
                    { Services.favorites.toggleRoute(routeId, direction) },
                    color = Palette.Heart,
                    onColor = Color.White,
                    iconOnly = true,
                )
            }
        }
        item {
            ActionRow(spec) {
                PillAction(Icons.Rounded.Map, stringResource(R.string.open_map), onOpenRoute)
                PillAction(Icons.Rounded.PhoneAndroid, stringResource(R.string.open_on_phone), openOnPhone)
            }
        }
        item {
            Column(contentModifier(spec), horizontalAlignment = Alignment.CenterHorizontally) {
                laterTimes(current, now)?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                Text(
                    stringResource(R.string.from_stop, name.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The station's icon and what kind of stop it is, above its name. */
@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.StationKind(look: StationLook?, spec: androidx.wear.compose.material3.lazy.TransformationSpec) {
    Row(
        contentModifier(spec),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationIcon(look, size = 20.dp)
        Text(stringResource(stopKind(look?.mode ?: Mode.BUS)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
fun MapChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .widthIn(max = 160.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun StopMapScreen(vm: StopViewModel) {
    LiveWhileVisible(vm)
    val name by vm.name.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val layer by vm.mapLayer.collectAsStateWithLifecycle()
    val me by rememberUserLocation()
    val camera = rememberMapCamera()
    val frame = location?.let { MapFrame.Focus(it.first, it.second, STOP_MAP_ZOOM) }
    val chrome = rememberMapChrome()
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                camera = camera,
                frame = frame,
                onMapTap = chrome::toggle,
                lines = layer.lines.map { MapLine(it.points, routeColor(it.color)) },
                stops = listOfNotNull(location?.let { stationMapStop(vm.stopId, it.first, it.second, selected = true) }),
                vehicles = layer.vehicles.map { MapVehicle(it.id, it.lat, it.lon, it.name, routeColor(it.color), it.heading) },
                me = me,
                ambient = LocalAmbient.current,
            )
            name?.let {
                MapControls(chrome, Modifier.align(Alignment.TopCenter).padding(top = 30.dp)) {
                    MapChip {
                        Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
