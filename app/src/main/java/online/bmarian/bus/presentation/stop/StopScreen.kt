package online.bmarian.bus.presentation.stop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.directionFromTripId
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.presentation.ActionRow
import online.bmarian.bus.presentation.Eta
import online.bmarian.bus.presentation.PillAction
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.LineHeader
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.RealtimeIcon
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.etaText
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.mercatorX
import online.bmarian.bus.presentation.map.mercatorY
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.surface
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.routeColor
import java.time.LocalDateTime

// Far enough out to see the buses that are a few minutes away.
private const val STOP_MAP_ZOOM = 15f
private const val ME_ZOOM = 16f

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
    val following by FollowManager.state.collectAsStateWithLifecycle()

    val sorted = remember(ui, favoriteRoutes) {
        ui?.departures?.sortedWith(compareBy({ if (vm.isDepartureFavorite(it)) 0 else 1 }, { it.minutesLeft }))
    }

    ListScreen { spec ->
        item { Title(name ?: "…", spec) }
        item {
            val favorite = vm.stopId in favoriteStops
            ActionRow(spec) {
                PillAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(R.string.favorite_stop),
                    { Services.favorites.toggleStop(vm.stopId) },
                    checked = favorite,
                )
                PillAction(Icons.Rounded.Map, stringResource(R.string.open_map), onOpenMap)
            }
        }
        when {
            failed -> {
                item { Message(stringResource(R.string.load_failed), spec) }
                item {
                    FilledTonalButton(
                        onClick = vm::load,
                        modifier = itemModifier(spec),
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        label = { Text(stringResource(R.string.retry)) },
                    )
                }
            }
            sorted == null -> item { Loading(spec) }
            sorted.isEmpty() -> item { Message(stringResource(R.string.no_schedule), spec) }
            else -> items(sorted.size, key = { sorted[it].shortName }) { i ->
                val departure = sorted[i]
                val now = ui!!.now
                val followed = following?.followed?.let { it.routeId == departure.routeId && it.tripId == departure.tripId && it.stopId == vm.stopId } == true
                TitleCard(
                    onClick = { onOpenDeparture(departure) },
                    modifier = itemModifier(spec),
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

/** One departure, like a Google Maps trip: when it comes, and a button to follow it. */
@Composable
fun DepartureScreen(vm: StopViewModel, routeId: Int, tripId: String, onOpenRoute: () -> Unit) {
    LiveWhileVisible(vm)
    val context = LocalContext.current
    val name by vm.name.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favoriteRoutes by Services.favorites.routes.collectAsStateWithLifecycle()
    val following by FollowManager.state.collectAsStateWithLifecycle()
    val departure = ui?.departures?.firstOrNull { it.routeId == routeId && it.tripId == tripId }
    val direction = directionFromTripId(tripId)
    val isFollowing = following?.followed?.let { it.stopId == vm.stopId && it.routeId == routeId && it.tripId == tripId } == true

    fun follow() {
        val d = departure ?: return
        FollowManager.start(
            context,
            Followed(vm.stopId, name.orEmpty(), routeId, tripId, d.shortName, d.color, d.destination()),
        )
    }
    // Wear OS 4 and later ask before an app may show its countdown on the watch face.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { follow() }

    val onFollow = {
        when {
            isFollowing -> FollowManager.stop(context)
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
                    Text(etaText(minutes, now), style = MaterialTheme.typography.numeralSmall, color = color)
                }
                Text(
                    stringResource(if (live) R.string.live else R.string.scheduled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Follow sits with the other actions; filled while this bus is being followed.
        item {
            val favorite = favoriteRoutes.any { it.routeId == routeId && it.direction == direction }
            ActionRow(spec) {
                PillAction(
                    Icons.Rounded.NotificationsActive,
                    stringResource(if (isFollowing) R.string.stop_following else R.string.follow),
                    onFollow,
                    checked = isFollowing,
                )
                PillAction(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(R.string.favorite_line),
                    { Services.favorites.toggleRoute(routeId, direction) },
                    checked = favorite,
                )
                PillAction(Icons.Rounded.Map, stringResource(R.string.open_map), onOpenRoute)
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
    val scope = rememberCoroutineScope()
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
                ambient = LocalAmbient.current,
                clearTop = 62.dp,
                clearBottom = 68.dp,
            )
            name?.let {
                MapChip(Modifier.align(Alignment.TopCenter).padding(top = 30.dp)) {
                    Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            me?.let { fix ->
                FilledTonalIconButton(
                    onClick = {
                        camera.x = mercatorX(fix.lon)
                        camera.y = mercatorY(fix.lat)
                        camera.targetZoom = ME_ZOOM
                        scope.launch { camera.zoom.animateTo(ME_ZOOM) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.my_location))
                }
            }
        }
    }
}
