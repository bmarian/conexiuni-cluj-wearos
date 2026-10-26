package online.bmarian.bus.presentation.journey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import online.bmarian.bus.R
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.LiveStream
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.follow.Journey
import online.bmarian.bus.follow.JourneyLeg
import online.bmarian.bus.follow.JourneyMirror
import online.bmarian.bus.follow.JourneySync
import online.bmarian.bus.follow.StepType
import online.bmarian.bus.follow.TripStep
import online.bmarian.bus.follow.minutesUntil
import online.bmarian.bus.follow.upcomingSteps
import online.bmarian.bus.logic.bearingDegrees
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.presentation.Countdown
import online.bmarian.bus.presentation.FitText
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.LocalAmbient
import online.bmarian.bus.presentation.clockText
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.map.MapFollow
import online.bmarian.bus.presentation.map.MapFrame
import online.bmarian.bus.presentation.map.MapLabel
import online.bmarian.bus.presentation.map.MapLine
import online.bmarian.bus.presentation.map.MapStop
import online.bmarian.bus.presentation.map.MapVehicle
import online.bmarian.bus.presentation.map.TransitMap
import online.bmarian.bus.presentation.map.rememberMapCamera
import online.bmarian.bus.presentation.map.rememberUserLocation
import online.bmarian.bus.presentation.rememberClock
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.theme.LineLooks
import online.bmarian.bus.presentation.theme.LocalLineLooks
import online.bmarian.bus.presentation.theme.manyLines

private val PILL_BOTTOM = 18.dp
private val PILL_MAX_WIDTH = 172.dp
private val RECENTER_BOTTOM = 62.dp
private val STEP_MARK = 30.dp
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
fun JourneyScreen(onEnded: () -> Unit) {
    val context = LocalContext.current
    val sync by JourneyMirror.state.collectAsStateWithLifecycle()
    val plan by JourneyMirror.plan.collectAsStateWithLifecycle()
    val current = sync
    LaunchedEffect(current == null) { if (current == null) onEnded() }
    if (current == null) return
    val trip = plan?.takeIf { it.id == current.id }
    var steps by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = steps && trip != null) { steps = false }

    if (steps || trip == null) {
        TripSteps(current, trip, onMap = { steps = false }, onEnd = { JourneyMirror.end(context, current.id) })
        return
    }
    val nowMs by rememberClock()
    val eta = current.etaAt?.takeIf { current.stepType != StepType.ARRIVED }
    val left = eta?.let { stringResource(R.string.eta_minutes, minutesUntil(it, nowMs)) + " · " + clockText(it) }
    ScreenScaffold(timeText = left?.let { label -> { TimeText { timeTextCurvedText(label) } } }) {
        Box(Modifier.fillMaxSize()) {
            TripMap(current, trip)
            StepPill(current, trip, Modifier.align(Alignment.BottomCenter).padding(bottom = PILL_BOTTOM)) { steps = true }
        }
    }
}

@Composable
private fun StepPill(sync: JourneySync, plan: Journey, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .widthIn(max = PILL_MAX_WIDTH)
            .squish(press)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val line = sync.line
        if (line != null && (sync.stepType == StepType.WAIT || sync.stepType == StepType.RIDE)) LineBadge(line, sync.color.orEmpty(), height = 20.dp)
        else Icon(stepIcon(sync.stepType), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        if (sync.countsDown) Countdown(sync.arrivalAt!!, MaterialTheme.typography.labelLarge, MaterialTheme.colorScheme.onSurface)
        else {
            val leg = plan.legs.getOrNull(sync.legIndex)
            val text = when (sync.stepType) {
                StepType.WALK -> leg?.boarding?.name
                StepType.WALK_END -> plan.destination.name
                StepType.ARRIVED -> sync.step
                else -> sync.detail
            } ?: sync.detail ?: sync.step
            FitText(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun TripSteps(sync: JourneySync, plan: Journey?, onMap: () -> Unit, onEnd: () -> Unit) {
    val upcoming = remember(plan, sync.stepType, sync.legIndex) { plan?.let { upcomingSteps(it, sync.stepType, sync.legIndex) }.orEmpty() }
    val edge: (@Composable BoxScope.() -> Unit)? = if (plan == null) null else ({
        EdgeButton(onClick = onMap, buttonSize = EdgeButtonSize.Small) {
            Icon(Icons.Rounded.Map, contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(R.string.map), Modifier.padding(start = 4.dp))
        }
    })
    ListScreen(edgeButton = edge) { spec ->
        item {
            val eta = sync.etaAt?.takeIf { sync.stepType != StepType.ARRIVED }
            Text(
                stringResource(R.string.journey_to, sync.title) + eta?.let { " · " + stringResource(R.string.nav_arrive_at, clockText(it)) }.orEmpty(),
                contentModifier(spec).padding(horizontal = 18.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            Row(
                contentModifier(spec)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val riding = sync.stepType == StepType.WAIT || sync.stepType == StepType.RIDE
                StepMark(stepIcon(sync.stepType), sync.line?.takeIf { riding }, sync.color, MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    FitText(sync.step, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 3)
                    if (sync.countsDown) Countdown(sync.arrivalAt!!, MaterialTheme.typography.labelLarge, MaterialTheme.colorScheme.onPrimaryContainer)
                    else sync.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        items(upcoming.size) { i ->
            val step = upcoming[i]
            Row(
                contentModifier(spec).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                val title: String
                val detail: String
                when (step) {
                    is TripStep.Board -> {
                        StepMark(Icons.Rounded.DirectionsBus, step.leg.lines.first(), step.leg.colors.first(), muted)
                        title = stringResource(R.string.board_line, step.leg.lines.first())
                        detail = step.leg.boarding.name
                    }
                    is TripStep.Ride -> {
                        StepMark(Icons.Rounded.Flag, null, null, muted)
                        title = stringResource(R.string.get_off_at, step.leg.alighting.name)
                        detail = pluralStringResource(R.plurals.after_stops, step.leg.stops.lastIndex, step.leg.stops.lastIndex)
                    }
                    is TripStep.WalkEnd -> {
                        StepMark(Icons.AutoMirrored.Rounded.DirectionsWalk, null, null, muted)
                        title = stringResource(R.string.walk_to_destination)
                        detail = step.destination.name
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Box(contentModifier(spec).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                CompactButton(
                    onClick = onEnd,
                    icon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                    label = { Text(stringResource(R.string.end_journey)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                )
            }
        }
    }
}

@Composable
private fun StepMark(icon: ImageVector, line: String?, color: String?, tint: Color) {
    Box(Modifier.size(STEP_MARK), contentAlignment = Alignment.Center) {
        if (line != null) LineBadge(line, color.orEmpty(), height = 22.dp)
        else Icon(icon, null, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
private fun TripMap(sync: JourneySync, plan: Journey) {
    val camera = rememberMapCamera()
    val phoneFix = sync.lat?.let { lat -> sync.lon?.let { lon -> Fix(lat, lon, 0f, sync.bearing) } }
    val me = withHeading(phoneFix ?: watchFix(needed = phoneFix == null))
    val leg = plan.legs.getOrNull(sync.legIndex) ?: plan.legs.last()
    val vehicles by produceState(emptyList<Vehicle>(), leg) {
        LiveStream.live(emptyList(), leg.tripIds, plan.city).collect { value = it.vehiclesByTrip.values.flatten() }
    }
    val looks = LocalLineLooks.current
    val apart = manyLines(plan.legs.map { it.lines.firstOrNull().orEmpty() })
    val lines = remember(plan, sync.legIndex, looks) {
        buildList {
            plan.walks.filter { it.size >= 2 }.forEach { walk -> add(MapLine(walk.map { it.lat to it.lon }, Walk, dotted = true)) }
            plan.legs.forEachIndexed { i, l ->
                val alpha = if (i == sync.legIndex) 1f else 0.5f
                add(MapLine(pathOf(l), looks.color(l.lines.firstOrNull(), l.colors.firstOrNull().orEmpty(), apart).copy(alpha = alpha)))
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
            vehicles = vehicles.mapNotNull { v -> busOf(leg, v, looks, apart) },
            me = me,
            ambient = ambient,
            follow = follow.takeIf { following },
            onFollowLost = {
                camera.framed = true
                following = false
            },
        )
        if (me != null && !ambient && !following) {
            FilledTonalIconButton(
                onClick = {
                    camera.framed = false
                    following = true
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = RECENTER_BOTTOM),
            ) {
                Icon(Icons.Rounded.Navigation, contentDescription = stringResource(R.string.journey_recenter))
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

private fun busOf(leg: JourneyLeg, v: Vehicle, looks: LineLooks, apart: Boolean): MapVehicle? {
    if (v.latitude == 0.0 || v.longitude == 0.0) return null
    val i = leg.routeIds.indexOf(v.routeId).takeIf { it >= 0 } ?: 0
    val path = pathOf(leg)
    if (path.none { haversineMeters(it.first, it.second, v.latitude, v.longitude) <= BUS_OFF_PATH_M }) return null
    val line = leg.lines.getOrNull(i) ?: leg.lines.first()
    return MapVehicle("${v.id}", v.latitude, v.longitude, line, looks.color(line, leg.colors.getOrNull(i) ?: leg.colors.first(), apart), v.heading, vehicle = v)
}
