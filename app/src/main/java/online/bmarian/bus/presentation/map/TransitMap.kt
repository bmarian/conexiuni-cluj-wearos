package online.bmarian.bus.presentation.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.presentation.Icons
import online.bmarian.bus.presentation.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tan

private const val MIN_ZOOM = 10f
private const val MAX_ZOOM = 18f
private const val MAX_TILE_ZOOM = 18
// One bezel click. The website zoomed a whole level per click, which jumps too far.
private const val ZOOM_PER_DETENT = 0.5f
// Fitted routes stop here, like the website's watch map.
private const val FIT_MAX_ZOOM = 16f
private const val FIT_SNAP = 0.25f
// A 256 px web tile on the 412 px wide watch browser is about 62% of the screen.
private val TILE_SIZE = 128.dp
// Same width as Wear's own edge swipe-to-dismiss zone.
private val BACK_EDGE = 30.dp

private val CLUJ_CENTER = 46.7712 to 23.6236
private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
private val ME_BLUE = Color(0xFF3B82F6)

fun mercatorX(lon: Double) = (lon + 180.0) / 360.0
fun mercatorY(lat: Double): Double {
    val rad = Math.toRadians(lat)
    return (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0
}

class MapLine(val points: List<Pair<Double, Double>>, val color: Color)
class MapStop(val id: String, val lat: Double, val lon: Double, val color: Color, val selected: Boolean = false)
class MapLabel(val id: String, val lat: Double, val lon: Double, val text: String, val live: Boolean, val rank: Int)
class MapVehicle(val id: String, val lat: Double, val lon: Double, val name: String, val color: Color, val heading: Double)

sealed interface MapFrame {
    class Fit(val points: List<Pair<Double, Double>>) : MapFrame
    class Focus(val lat: Double, val lon: Double, val zoom: Float) : MapFrame
}

@Stable
class MapCamera {
    var x by mutableDoubleStateOf(mercatorX(CLUJ_CENTER.second))
    var y by mutableDoubleStateOf(mercatorY(CLUJ_CENTER.first))
    val zoom = Animatable(13f)
    var framed by mutableStateOf(false)
    var targetZoom = 13f
}

@Composable
fun rememberMapCamera() = remember { MapCamera() }

private class Projected(val x: Double, val y: Double)

@Composable
fun TransitMap(
    camera: MapCamera,
    frame: MapFrame?,
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    stops: List<MapStop> = emptyList(),
    labels: List<MapLabel> = emptyList(),
    vehicles: List<MapVehicle> = emptyList(),
    me: Fix? = null,
) {
    val density = LocalDensity.current
    val tilePx = with(density) { TILE_SIZE.toPx() }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val measurer = rememberTextMeasurer()
    val tileVersion by TileLoader.version

    fun worldSize(zoom: Float) = tilePx * 2.0.pow(zoom.toDouble())

    fun zoomTo(target: Float) {
        camera.targetZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scope.launch { camera.zoom.animateTo(camera.targetZoom, tween(180)) }
    }

    LaunchedEffect(frame, size) {
        if (camera.framed || frame == null || size == IntSize.Zero) return@LaunchedEffect
        when (frame) {
            is MapFrame.Focus -> {
                camera.x = mercatorX(frame.lon)
                camera.y = mercatorY(frame.lat)
                camera.targetZoom = frame.zoom
                camera.zoom.snapTo(frame.zoom)
                camera.framed = true
            }
            is MapFrame.Fit -> {
                if (frame.points.isEmpty()) return@LaunchedEffect
                fit(camera, frame.points, size, tilePx)
                camera.framed = true
            }
        }
    }

    LaunchedEffect(size) {
        snapshotFlow { Triple(camera.x, camera.y, camera.zoom.value) }.collect { (cx, cy, zoom) ->
            if (size == IntSize.Zero) return@collect
            forEachVisibleTile(cx, cy, zoom, size, tilePx) { z, x, y, _, _ -> TileLoader.request(scope, z, x, y) }
        }
    }

    val projectedLines = remember(lines) {
        lines.map { line -> line.color to line.points.map { Projected(mercatorX(it.second), mercatorY(it.first)) } }
    }
    val labelStyle = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    val labelLayouts = remember(labels) {
        labels.sortedBy { it.rank }.map { label ->
            Triple(label, Projected(mercatorX(label.lon), mercatorY(label.lat)),
                measurer.measure(label.text, labelStyle.copy(color = if (label.live) Palette.Accent else Color(0xFFE2E8F0))))
        }
    }
    val vehicleStyle = remember { TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White) }
    val vehicleLayouts = remember(vehicles) { vehicles.associate { it.id to measurer.measure(it.name, vehicleStyle) } }
    val busPath = remember { PathParser().parsePathString(Icons.BUS_STOP_PATH).toPath() }
    val arrowPath = remember { PathParser().parsePathString("M12 2L21 21l-9-4-9 4 9-19z").toPath() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels != 0f) zoomTo(camera.targetZoom + sign(event.verticalScrollPixels) * ZOOM_PER_DETENT)
                true
            }
            .requestFocusOnHierarchyActive()
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { zoomTo(camera.targetZoom + 1f) })
            }
            .pointerInput(Unit) {
                val edge = BACK_EDGE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Left alone, a swipe from the left edge goes back: not every watch has a back button.
                    if (down.position.x < edge) return@awaitEachGesture
                    var moved = Offset.Zero
                    var dragging = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val pan = event.calculatePan()
                        val gestureZoom = event.calculateZoom()
                        if (!dragging) {
                            moved += pan
                            dragging = moved.getDistance() > viewConfiguration.touchSlop || gestureZoom != 1f
                        }
                        if (dragging) {
                            val world = worldSize(camera.zoom.value)
                            camera.x -= pan.x / world
                            camera.y -= pan.y / world
                            if (gestureZoom != 1f) {
                                val next = (camera.zoom.value + log2(gestureZoom)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                camera.targetZoom = next
                                scope.launch { camera.zoom.snapTo(next) }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        if (tileVersion < 0) return@Canvas
        drawRect(Color(0xFF0E0E0E))
        val zoom = camera.zoom.value
        val world = worldSize(zoom)
        val cx = camera.x
        val cy = camera.y
        fun toScreen(px: Double, py: Double) = Offset(
            ((px - cx) * world + this.size.width / 2).toFloat(),
            ((py - cy) * world + this.size.height / 2).toFloat(),
        )

        drawTiles(cx, cy, zoom, IntSize(this.size.width.toInt(), this.size.height.toInt()), tilePx)

        val lineWidth = 3.dp.toPx()
        for ((color, points) in projectedLines) {
            if (points.size < 2) continue
            val path = Path()
            points.forEachIndexed { i, p ->
                val s = toScreen(p.x, p.y)
                if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
            }
            drawPath(path, color.copy(alpha = 0.85f), style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        if (me != null) {
            val at = toScreen(mercatorX(me.lon), mercatorY(me.lat))
            // Metres to pixels at this latitude, for the accuracy circle.
            val pxPerMeter = world / (EARTH_CIRCUMFERENCE_M * kotlin.math.cos(Math.toRadians(me.lat)))
            drawCircle(ME_BLUE.copy(alpha = 0.15f), (me.accuracyMeters * pxPerMeter).toFloat(), at)
            drawCircle(ME_BLUE, (me.accuracyMeters * pxPerMeter).toFloat(), at, style = Stroke(1.dp.toPx()))
        }

        val stopRadius = 6.dp.toPx()
        for (stop in stops.sortedBy { it.selected }) {
            val s = toScreen(mercatorX(stop.lon), mercatorY(stop.lat))
            val r = if (stop.selected) stopRadius * 1.35f else stopRadius
            drawCircle(Color.White, r + 1.dp.toPx(), s)
            drawCircle(stop.color, r, s)
            val iconSize = r * 1.15f
            translate(s.x - iconSize / 2, s.y - iconSize / 2) {
                scale(iconSize / 24f, iconSize / 24f, pivot = Offset.Zero) { drawPath(busPath, Color.White) }
            }
        }

        if (me != null) {
            val at = toScreen(mercatorX(me.lon), mercatorY(me.lat))
            drawCircle(Color.White, 7.dp.toPx(), at)
            drawCircle(ME_BLUE, 5.dp.toPx(), at)
        }

        drawLabels(labelLayouts.map { (label, p, layout) -> Triple(label, toScreen(p.x, p.y), layout) }, stopRadius)

        val vehicleRadius = 10.dp.toPx()
        for (vehicle in vehicles) {
            val s = toScreen(mercatorX(vehicle.lon), mercatorY(vehicle.lat))
            drawCircle(Color.White, vehicleRadius + 1.dp.toPx(), s)
            drawCircle(vehicle.color, vehicleRadius, s)
            vehicleLayouts[vehicle.id]?.let { layout ->
                drawText(layout, topLeft = Offset(s.x - layout.size.width / 2f, s.y - layout.size.height / 2f))
            }
            val badge = Offset(s.x + vehicleRadius * 0.75f, s.y + vehicleRadius * 0.75f)
            val badgeRadius = 4.5.dp.toPx()
            drawCircle(Color.White, badgeRadius + 0.5.dp.toPx(), badge)
            drawCircle(Color(0xD90F172A), badgeRadius, badge)
            val arrow = badgeRadius * 1.25f
            rotate(vehicle.heading.toFloat(), pivot = badge) {
                translate(badge.x - arrow / 2, badge.y - arrow / 2) {
                    scale(arrow / 24f, arrow / 24f, pivot = Offset.Zero) { drawPath(arrowPath, Color.White) }
                }
            }
        }
    }
}

// Soonest first. A label that would cover one already placed stays hidden until zooming
// in makes room for it.
private fun DrawScope.drawLabels(labels: List<Triple<MapLabel, Offset, TextLayoutResult>>, stopRadius: Float) {
    val placed = mutableListOf<Rect>()
    val padX = 4.dp.toPx()
    val padY = 1.dp.toPx()
    val gap = 2.dp.toPx()
    val lift = stopRadius + 3.dp.toPx()
    for ((label, at, layout) in labels) {
        val w = layout.size.width + padX * 2
        val h = layout.size.height + padY * 2
        val box = Rect(at.x - w / 2, at.y - lift - h, at.x + w / 2, at.y - lift)
        if (box.right < 0 || box.left > size.width || box.bottom < 0 || box.top > size.height) continue
        if (placed.any { box.left < it.right + gap && box.right + gap > it.left && box.top < it.bottom + gap && box.bottom + gap > it.top }) continue
        placed += box
        val corner = CornerRadius(4.dp.toPx())
        drawRoundRect(Color(0xEB0F172A), box.topLeft, box.size, corner)
        drawRoundRect(if (label.live) Palette.AccentDeep else Palette.SurfaceLine, box.topLeft, box.size, corner, style = Stroke(1.dp.toPx()))
        drawText(layout, topLeft = Offset(box.left + padX, box.top + padY))
    }
}

private inline fun forEachVisibleTile(
    cx: Double, cy: Double, zoom: Float, size: IntSize, tilePx: Float,
    block: (z: Int, x: Int, y: Int, left: Float, top: Float) -> Unit,
) {
    val tz = zoom.roundToInt().coerceIn(0, MAX_TILE_ZOOM)
    val world = tilePx * 2.0.pow(zoom.toDouble())
    val tiles = 1 shl tz
    val minX = floor((cx - size.width / 2.0 / world) * tiles).toInt()
    val maxX = floor((cx + size.width / 2.0 / world) * tiles).toInt()
    val minY = floor((cy - size.height / 2.0 / world) * tiles).toInt()
    val maxY = floor((cy + size.height / 2.0 / world) * tiles).toInt()
    for (x in max(minX, 0)..min(maxX, tiles - 1)) {
        for (y in max(minY, 0)..min(maxY, tiles - 1)) {
            val left = ((x.toDouble() / tiles - cx) * world + size.width / 2).toFloat()
            val top = ((y.toDouble() / tiles - cy) * world + size.height / 2).toFloat()
            block(tz, x, y, left, top)
        }
    }
}

private fun DrawScope.drawTiles(cx: Double, cy: Double, zoom: Float, size: IntSize, tilePx: Float) {
    val world = tilePx * 2.0.pow(zoom.toDouble())
    forEachVisibleTile(cx, cy, zoom, size, tilePx) { z, x, y, left, top ->
        val drawn = (world / (1 shl z)).toFloat()
        val image = TileLoader.cached(z, x, y)
        if (image != null) {
            drawImage(
                image,
                dstOffset = androidx.compose.ui.unit.IntOffset(floor(left).toInt(), floor(top).toInt()),
                dstSize = IntSize((drawn + 1).toInt(), (drawn + 1).toInt()),
            )
            return@forEachVisibleTile
        }
        // Until it arrives, stretch a parent tile that is already here.
        for (up in 1..3) {
            val pz = z - up
            if (pz < 0) break
            val parent = TileLoader.cached(pz, x shr up, y shr up) ?: continue
            val part = parent.width / (1 shl up)
            val offX = (x - ((x shr up) shl up)) * part
            val offY = (y - ((y shr up) shl up)) * part
            drawImage(
                parent,
                srcOffset = androidx.compose.ui.unit.IntOffset(offX, offY),
                srcSize = IntSize(part, part),
                dstOffset = androidx.compose.ui.unit.IntOffset(floor(left).toInt(), floor(top).toInt()),
                dstSize = IntSize((drawn + 1).toInt(), (drawn + 1).toInt()),
            )
            break
        }
    }
}

// Like Leaflet's fitBounds with padding: the route sits inside the round screen, clear of
// the title above and the buttons below.
private suspend fun fit(camera: MapCamera, points: List<Pair<Double, Double>>, size: IntSize, tilePx: Float) {
    val xs = points.map { mercatorX(it.second) }
    val ys = points.map { mercatorY(it.first) }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val side = size.width * 0.12
    val top = size.height * 0.2
    val bottom = size.height * 0.24
    val availW = size.width - side * 2
    val availH = size.height - top - bottom
    val spanX = max(maxX - minX, 1e-9)
    val spanY = max(maxY - minY, 1e-9)
    val raw = log2(min(availW / (spanX * tilePx), availH / (spanY * tilePx))).toFloat()
    val zoom = (floor(raw / FIT_SNAP) * FIT_SNAP).coerceIn(MIN_ZOOM, FIT_MAX_ZOOM)
    val world = tilePx * 2.0.pow(zoom.toDouble())
    camera.x = (minX + maxX) / 2
    camera.y = (minY + maxY) / 2 - (top - bottom) / 2 / world
    camera.targetZoom = zoom
    camera.zoom.snapTo(zoom)
}

@Composable
fun rememberUserLocation(): State<Fix?> {
    val context = LocalContext.current
    val updates = remember(context) { UserLocation.updates(context) }
    return updates.collectAsStateWithLifecycle(null)
}
