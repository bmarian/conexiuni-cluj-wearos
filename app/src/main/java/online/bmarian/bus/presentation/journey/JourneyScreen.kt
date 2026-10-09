package online.bmarian.bus.presentation.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SegmentedCircularProgressIndicator
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.VehicleStream
import online.bmarian.bus.follow.Journey
import online.bmarian.bus.follow.JourneyLeg
import online.bmarian.bus.follow.JourneyMirror
import online.bmarian.bus.follow.JourneySync
import online.bmarian.bus.follow.StepType
import online.bmarian.bus.logic.bearingDegrees
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.presentation.Countdown
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.map.MapFollow
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLabel
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.stop.MapChip
import online.bmarian.bus.presentation.theme.routeColor

private const val MAX_SEGMENTS = 20
private const val BUS_OFF_PATH_M = 150.0
private const val HEADING_MIN_MOVE_M = 8.0
private const val WALK_JOIN_M = 80.0
private val Walk = Color(0xFF38BDF8)
private val Boarding = Color(0xFF10B981)
private val Change = Color(0xFFA855F7)
private val Alighting = Color(0xFFF59E0B)
private val Destination = Color(0xFFF43F5E)

private fun stepIcon(type: String?): ImageVector = when (type) {
    StepType.WAIT -> Icons.Rounded.HourglassTop
    StepType.RIDE -> Icons.Rounded.DirectionsBus
    StepType.ARRIVED -> Icons.Rounded.Flag
    else -> Icons.AutoMirrored.Rounded.DirectionsWalk
}

@Composable
fun JourneyScreen(onEnded: () -> Unit, onMap: () -> Unit) {
    val context = LocalContext.current
    val sync by JourneyMirror.state.collectAsStateWithLifecycle()
    val current = sync
    LaunchedEffect(current == null) { if (current == null) onEnded() }
    if (current == null) return

    val total = current.totalStops
    val left = current.stopsLeft
    val counting = current.stepType == StepType.RIDE && total != null && left != null && total in 1..MAX_SEGMENTS
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            if (counting) {
                SegmentedCircularProgressIndicator(
                    segmentCount = total!!,
                    progress = { (total - left!!).toFloat() / total },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(progress = { current.progress ?: 0f }, modifier = Modifier.fillMaxSize())
            }
            Column(
                Modifier.align(Alignment.Center).padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(stepIcon(current.stepType), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    current.line?.let { LineBadge(it, current.color.orEmpty(), height = 24.dp) }
                }
                Text(
                    current.step,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val arrival = current.arrivalAt
                val countdown = arrival != null && (current.stepType == StepType.WALK || current.stepType == StepType.WAIT)
                if (countdown) Countdown(arrival!!, MaterialTheme.typography.numeralSmall, MaterialTheme.colorScheme.primary)
                current.detail?.takeIf { !countdown }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(
                    onClick = { JourneyMirror.end(context, current.id) },
                    modifier = Modifier.size(IconButtonDefaults.ExtraSmallButtonSize),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.end_journey), Modifier.size(18.dp))
                }
            }
            EdgeButton(onClick = onMap, buttonSize = EdgeButtonSize.ExtraSmall, modifier = Modifier.align(Alignment.BottomCenter)) {
                Icon(Icons.Rounded.Map, contentDescription = stringResource(R.string.map))
            }
        }
    }
}

@Composable
fun JourneyMapScreen(onEnded: () -> Unit) {
    val sync by JourneyMirror.state.collectAsStateWithLifecycle()
    val plan by JourneyMirror.plan.collectAsStateWithLifecycle()
    val current = sync
    LaunchedEffect(current == null) { if (current == null) onEnded() }
    if (current == null) return
    val trip = plan
    ScreenScaffold {
        if (trip == null || trip.id != current.id) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.journey_plan_loading),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            TripMap(current, trip)
        }
    }
}

@Composable
private fun TripMap(sync: JourneySync, plan: Journey) {
    val camera = rememberMapCamera()
    val phoneFix = sync.lat?.let { lat -> sync.lon?.let { lon -> Fix(lat, lon, 0f, sync.bearing) } }
    val me = withHeading(phoneFix ?: watchFix(needed = phoneFix == null))
    val leg = plan.legs.getOrNull(sync.legIndex) ?: plan.legs.last()
    val vehicles by produceState(emptyList<Vehicle>(), leg) {
        VehicleStream.vehicles(leg.tripIds).collect { byTrip -> value = byTrip.values.flatten() }
    }
    val lines = remember(plan, sync.legIndex) {
        buildList {
            plan.walks.filter { it.size >= 2 }.forEach { walk -> add(MapLine(walk.map { it.lat to it.lon }, Walk, dotted = true)) }
            plan.legs.forEachIndexed { i, l ->
                val alpha = if (i == sync.legIndex) 1f else 0.5f
                add(MapLine(pathOf(l), routeColor(l.colors.firstOrNull().orEmpty()).copy(alpha = alpha)))
            }
        }
    }
    val stops = remember(plan) {
        plan.legs.flatMapIndexed { i, l ->
            listOf(
                MapStop("b$i", l.boarding.lat, l.boarding.lon, if (i == 0) Boarding else Change),
                MapStop("a$i", l.alighting.lat, l.alighting.lon, if (i == plan.legs.lastIndex) Destination else Alighting),
            )
        }
    }
    val target = when (sync.stepType) {
        StepType.RIDE -> leg.alighting
        StepType.WALK_END, StepType.ARRIVED -> plan.destination
        else -> leg.boarding
    }
    val labels = listOf(MapLabel("target", target.lat, target.lon, target.name, live = false, rank = 0))
    val frame = remember(plan.id, sync.legIndex) {
        val walk = plan.walks.firstOrNull { w ->
            w.isNotEmpty() && haversineMeters(w.last().lat, w.last().lon, leg.boarding.lat, leg.boarding.lon) < WALK_JOIN_M
        }.orEmpty().map { it.lat to it.lon }
        MapFrame.Fit(walk + pathOf(leg), side = 0.24, top = 0.28, bottom = 0.28)
    }
    LaunchedEffect(plan.id, sync.legIndex) { camera.framed = false }
    var following by rememberSaveable { mutableStateOf(true) }
    val follow = when (sync.stepType) {
        StepType.RIDE -> MapFollow(zoom = 14.8, tilt = 45.0)
        StepType.WAIT -> MapFollow(zoom = 16.3, tilt = 0.0)
        else -> MapFollow(zoom = 16.5, tilt = 40.0)
    }
    val ambient = LocalAmbient.current
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            camera = camera,
            frame = frame,
            lines = lines,
            stops = stops,
            labels = labels,
            vehicles = vehicles.mapNotNull { v -> busOf(leg, v) },
            me = me,
            ambient = ambient,
            follow = follow.takeIf { following },
            onFollowLost = {
                camera.framed = true
                following = false
            },
        )
        MapChip(Modifier.align(Alignment.TopCenter).padding(top = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(stepIcon(sync.stepType), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(sync.step, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (me != null && !ambient) {
            FilledTonalIconButton(
                onClick = {
                    if (following) camera.framed = false
                    following = !following
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                if (following) Icon(Icons.Rounded.ZoomOutMap, contentDescription = stringResource(R.string.journey_overview))
                else Icon(Icons.Rounded.Navigation, contentDescription = stringResource(R.string.journey_recenter))
            }
        }
    }
}

@Composable
private fun watchFix(needed: Boolean): Fix? {
    if (!needed) return null
    val fix by rememberUserLocation()
    return fix
}

@Composable
private fun withHeading(fix: Fix?): Fix? {
    var last by remember { mutableStateOf<Fix?>(null) }
    var heading by remember { mutableStateOf<Float?>(null) }
    if (fix == null) return null
    if (fix.bearing != null) return fix
    val before = last
    if (before == null || haversineMeters(before.lat, before.lon, fix.lat, fix.lon) >= HEADING_MIN_MOVE_M) {
        if (before != null) heading = bearingDegrees(before.lat, before.lon, fix.lat, fix.lon).toFloat()
        SideEffect { last = fix }
    }
    return heading?.let { Fix(fix.lat, fix.lon, fix.accuracyMeters, it) } ?: fix
}

private fun pathOf(leg: JourneyLeg): List<Pair<Double, Double>> =
    (leg.path.takeIf { it.size >= 2 }?.map { it.lat to it.lon }) ?: leg.stops.map { it.lat to it.lon }

private fun busOf(leg: JourneyLeg, v: Vehicle): MapVehicle? {
    if (v.latitude == 0.0 || v.longitude == 0.0) return null
    val i = leg.routeIds.indexOf(v.routeId).takeIf { it >= 0 } ?: 0
    val path = pathOf(leg)
    val nearest = path.indices.minByOrNull { haversineMeters(path[it].first, path[it].second, v.latitude, v.longitude) } ?: 0
    if (haversineMeters(path[nearest].first, path[nearest].second, v.latitude, v.longitude) > BUS_OFF_PATH_M) return null
    val from = path[nearest.coerceAtMost(path.lastIndex - 1).coerceAtLeast(0)]
    val to = path[(nearest + 1).coerceAtMost(path.lastIndex)]
    val heading = if (from == to) 0.0 else bearingDegrees(from.first, from.second, to.first, to.second)
    return MapVehicle("${v.id}", v.latitude, v.longitude, leg.lines.getOrNull(i) ?: leg.lines.first(), routeColor(leg.colors.getOrNull(i) ?: leg.colors.first()), heading)
}
