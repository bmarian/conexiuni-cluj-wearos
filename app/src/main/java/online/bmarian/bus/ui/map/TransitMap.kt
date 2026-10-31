package online.bmarian.bus.ui.map

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.animation.doOnEnd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import online.bmarian.bus.Services
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.Fix
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.logic.LONG_PACE
import online.bmarian.bus.logic.Mode
import online.bmarian.bus.logic.RoutePath
import online.bmarian.bus.logic.SWEEP_MS
import online.bmarian.bus.logic.VehicleTrack
import online.bmarian.bus.logic.haversineMeters
import online.bmarian.bus.logic.paced
import online.bmarian.bus.logic.partBetween
import online.bmarian.bus.logic.pathOf
import online.bmarian.bus.logic.pathThrough
import online.bmarian.bus.ui.theme.Palette
import online.bmarian.bus.ui.theme.routeColor
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.iconTranslate
import org.maplibre.android.style.layers.PropertyFactory.iconTranslateAnchor
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

private const val MIN_ZOOM = 8.0
private const val MAX_ZOOM = 17.0
private const val FIT_MAX_ZOOM = 14.0
// Per bezel click.
private const val ZOOM_PER_DETENT = 0.5
private val TAP_REACH = 22.dp
private const val FOLLOW_TOP_SHARE = 0.25
// Same width as Wear's edge swipe-to-dismiss zone.
private val BACK_EDGE = 30.dp
// Cluj's, until the server's list first loads.
private val DEFAULT_CENTER = LatLng(46.7712, 23.6236)

private var started = false

private fun startMapLibre(context: Context) {
    if (started) return
    started = true
    val app = context.applicationContext
    MapLibre.getInstance(app)
    // MapLibre caches tiles itself.
    HttpRequestUtil.setOkHttpClient(Api.client.newBuilder().cache(null).build())
    OfflineManager.getInstance(app).setMaximumAmbientCacheSize(40L * 1024 * 1024, null)
}

/** [id] keeps a line the same one while its points change, so it's redrawn in place rather than grown again. */
class MapLine(val points: List<Pair<Double, Double>>, val color: Color, val dotted: Boolean = false, val name: String = "", val id: String = "")
class MapStop(
    val id: String,
    val lat: Double,
    val lon: Double,
    val color: Color,
    val selected: Boolean = false,
    val mode: Mode = Mode.BUS,
    val heart: Boolean = false,
)

fun stationMapStop(stopId: Int, lat: Double, lon: Double, selected: Boolean = false): MapStop {
    val look = Services.repository.network.index.value?.look(stopId)
    return MapStop("$stopId", lat, lon, look?.let { routeColor(it.color) } ?: Palette.StopGray, selected, look?.mode ?: Mode.BUS)
}
class MapLabel(val id: String, val lat: Double, val lon: Double, val text: String, val live: Boolean, val rank: Int)
/** [faded]: on the other direction now, counted on coming back. */
class MapVehicle(
    val id: String,
    val lat: Double,
    val lon: Double,
    val name: String,
    val color: Color,
    val heading: Double,
    val faded: Boolean = false,
    val vehicle: Vehicle? = null,
)

data class MapFollow(val zoom: Double, val tilt: Double)

// Each new [seq] ripples once.
class MapArrival(val lat: Double, val lon: Double, val color: Color, val vehicleId: String?, val seq: Int)

sealed interface MapFrame {
    // Fractions of the screen.
    class Fit(
        val points: List<Pair<Double, Double>>,
        val side: Double = 0.14,
        val top: Double = 0.38,
        val bottom: Double = 0.32,
    ) : MapFrame
    class Focus(val lat: Double, val lon: Double, val zoom: Float) : MapFrame
}

@Stable
class MapCamera {
    internal var map: MapLibreMap? = null
    internal var position: CameraPosition? = null
    private var pending: CameraPosition? = null
    var framed by mutableStateOf(false)

    fun centerOn(lat: Double, lon: Double, zoom: Float) {
        val target = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom.toDouble()).build()
        val m = map
        if (m == null) pending = target else m.animateCamera(CameraUpdateFactory.newCameraPosition(target), paced(435))
    }

    internal fun attach(m: MapLibreMap) {
        map = m
        pending?.let { m.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
        pending = null
        m.addOnCameraIdleListener { position = m.cameraPosition }
    }
}

@Composable
fun rememberMapCamera() = remember { MapCamera() }

@Composable
fun TransitMap(
    camera: MapCamera,
    frame: MapFrame?,
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    stops: List<MapStop> = emptyList(),
    labels: List<MapLabel> = emptyList(),
    vehicles: List<MapVehicle> = emptyList(),
    terminals: Boolean = false,
    arrows: Boolean = false,
    me: Fix? = null,
    ambient: Boolean = false,
    onStopTap: ((String) -> Unit)? = null,
    onMapTap: (() -> Unit)? = null,
    interactive: Boolean = true,
    follow: MapFollow? = null,
    onFollowLost: (() -> Unit)? = null,
    arrival: MapArrival? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnStopTap by rememberUpdatedState(onStopTap)
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnFollowLost by rememberUpdatedState(onFollowLost)
    var session by remember { mutableStateOf<MapSession?>(null) }

    val host = remember {
        startMapLibre(context)
        val center = Cities.info()?.let { LatLng(it.lat, it.lon) } ?: DEFAULT_CENTER
        val start = camera.position ?: CameraPosition.Builder().target(center).zoom(11.0).build()
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(start)
            .minZoomPreference(MIN_ZOOM)
            .maxZoomPreference(MAX_ZOOM)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
            .tiltGesturesEnabled(false)
            .rotateGesturesEnabled(false)
            .foregroundLoadColor(0xFF0C0C0C.toInt())
        MapHost(context, MapView(context, options).also { it.onCreate(null) })
    }
    host.edgePx = with(density) { BACK_EDGE.toPx() }
    host.interactive = interactive

    DisposableEffect(lifecycle, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.map.onStart()
                Lifecycle.Event.ON_RESUME -> host.map.onResume()
                Lifecycle.Event.ON_PAUSE -> host.map.onPause()
                Lifecycle.Event.ON_STOP -> host.map.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) host.map.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) host.map.onStop()
            camera.map = null
            session?.destroy()
            host.map.onDestroy()
        }
    }

    DisposableEffect(host) {
        host.map.getMapAsync { map ->
            map.setLatLngBoundsForCameraTarget(Cities.info()?.bounds?.let { LatLngBounds.from(it.north, it.east, it.south, it.west) })
            map.setStyle(Style.Builder().fromJson(darkStyle(context))) { style ->
                val created = MapSession(context, map, style) { currentOnFollowLost?.invoke() }
                created.waitForFirstFrame(host.map)
                map.addOnMapClickListener { latLng ->
                    val tap = currentOnStopTap
                    val id = tap?.let { created.stopAt(map.projection.toScreenLocation(latLng), with(density) { TAP_REACH.toPx() }) }
                    if (tap != null && id != null) tap(id) else currentOnMapTap?.invoke()
                    true
                }
                camera.attach(map)
                session = created
            }
        }
        onDispose {}
    }

    LaunchedEffect(session, lines) { session?.setLines(lines) }
    LaunchedEffect(session, lines, terminals, arrows) {
        session?.setEnds(
            if (terminals) lineStarts(lines) else emptyList(),
            if (terminals) lineEnds(lines) else emptyList(),
            if (arrows) startArrows(lines) else emptyList(),
        )
    }
    LaunchedEffect(session, stops) { session?.setStops(stops) }
    LaunchedEffect(session, labels) { session?.setLabels(labels) }
    LaunchedEffect(session, vehicles) { session?.setVehicles(vehicles) }
    val vehicleTrips = remember(vehicles) { vehicles.mapNotNull { it.vehicle?.tripId }.distinct().sorted() }
    val vehiclePaths by produceState(emptyMap<String, RoutePath>(), vehicleTrips) {
        value = runCatching { Services.repository.shapes(vehicleTrips) }.getOrDefault(emptyMap())
            .filterValues { it.size > 1 }.mapValues { pathOf(it.value) }
    }
    LaunchedEffect(session, vehiclePaths) { session?.setVehiclePaths(vehiclePaths) }
    LaunchedEffect(session, me) { session?.setMe(me) }
    LaunchedEffect(session, follow) { session?.setFollow(follow) }
    LaunchedEffect(session, arrival?.seq) { arrival?.let { session?.ripple(it.lat, it.lon, it.color, it.vehicleId) } }
    LaunchedEffect(session, ambient) {
        session?.setAmbient(ambient)
        host.setBackgroundColor(if (ambient) android.graphics.Color.BLACK else 0xFF0C0C0C.toInt())
    }

    val following = follow != null && me != null
    val shown = remember { booleanArrayOf(false) }
    LaunchedEffect(session, frame, camera.framed, following) {
        val s = session ?: return@LaunchedEffect
        if (following) shown[0] = true
        if (camera.framed || frame == null || following) return@LaunchedEffect
        while (host.width == 0 || host.height == 0) withFrameNanos {}
        val animate = shown[0]
        s.map.cancelTransitions()
        when (frame) {
            is MapFrame.Focus -> s.move(
                CameraPosition.Builder().target(LatLng(frame.lat, frame.lon)).zoom(frame.zoom.toDouble()).bearing(0.0).tilt(0.0).build(),
                animate,
            )
            is MapFrame.Fit -> {
                if (frame.points.isEmpty()) return@LaunchedEffect
                s.fit(frame, host.width, host.height, animate)
            }
        }
        camera.framed = true
        shown[0] = true
    }

    AndroidView(
        factory = { host },
        modifier = modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels != 0f) {
                    session?.map?.animateCamera(CameraUpdateFactory.zoomBy(sign(event.verticalScrollPixels) * ZOOM_PER_DETENT), 130)
                }
                true
            }
            .requestFocusOnHierarchyActive()
            .focusable(),
    )
}

@SuppressLint("ViewConstructor")
private class MapHost(context: Context, val map: MapView) : FrameLayout(context) {
    var edgePx = 0f
    var interactive = true

    init {
        addView(map, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!interactive) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Swiping back only from the edge; anywhere else the map keeps the touch.
            if (ev.x < edgePx) return false
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }
}

private var cachedStyle: String? = null

private fun darkStyle(context: Context): String =
    cachedStyle ?: context.assets.open("styles/dark.json").bufferedReader().use { it.readText() }.also { cachedStyle = it }

private const val SRC_LINES = "ct-lines"
private const val SRC_STOPS = "ct-stops"
private const val SRC_LABELS = "ct-labels"
private const val SRC_VEHICLES = "ct-vehicles"
private const val SRC_ENDS = "ct-ends"
private const val SRC_BADGES = "ct-badges"
private const val SRC_ARROWS = "ct-arrows"
private const val SRC_SWEEP = "ct-sweep"
private const val SRC_FADE = "ct-fade"
private const val SRC_RIPPLE = "ct-ripple"
private const val LAYER_STOPS = "ct-stops"
private const val MOVE_MS = (520 * LONG_PACE).toInt()
private const val LEAVE_MS = 140L
private const val RIPPLE_MS = (870 * LONG_PACE).toLong()
private const val RIPPLE_GAP_MS = (210 * LONG_PACE).toLong()
private const val FADED_VEHICLE_OPACITY = 0.5f
private const val FADED_VEHICLE_SIZE = 0.7f
private const val MIN_STEP_PX = 0.3
private const val MAX_IDLE_MS = 250L

private class MapSession(
    private val context: Context,
    val map: MapLibreMap,
    private val style: Style,
    private val onFollowLost: () -> Unit,
) {
    private val images = MapImages(context)
    private val added = HashSet<String>()
    private var me: Fix? = null
    private var follow: MapFollow? = null
    private var followed: MapFollow? = null
    private var followMove = 0
    private var settling = false
    private var vehicles: List<MapVehicle> = emptyList()
    private var paths: Map<String, RoutePath> = emptyMap()
    private val tracks = HashMap<String, VehicleTrack>()
    private val untracked = HashMap<String, String?>()
    private val drawn = HashMap<String, Pair<Pair<Double, Double>, Double>>()
    private var ambient = false
    private var moving = false
    private val frame = Choreographer.FrameCallback { stepVehicles() }
    private var lines: List<MapLine> = emptyList()
    private var hidden: Set<Int> = emptySet()
    private var growingKeys: Set<String> = emptySet()
    private var stops: List<MapStop> = emptyList()
    private var labels: List<MapLabel> = emptyList()
    private var badges: List<MapBadge> = emptyList()
    private var ends: List<MapEnd> = emptyList()
    private var arrows: List<MapArrow> = emptyList()
    private val reveal = LineReveal(context)
    private var revealing = false
    private val revealFrame = Choreographer.FrameCallback { stepReveal() }
    private var sweep: ValueAnimator? = null
    private var fade: ValueAnimator? = null
    private var ripple: ValueAnimator? = null
    private val bounces = HashMap<String, ValueAnimator>()
    private var pendingGrow: (() -> Unit)? = null
    private var aiming = 0
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val glide = MeGlide(handler) { applyMe(it) }

    init {
        listOf(SRC_LINES, SRC_SWEEP, SRC_FADE, SRC_RIPPLE, SRC_STOPS, SRC_ENDS, SRC_BADGES, SRC_ARROWS, SRC_LABELS, SRC_VEHICLES)
            .forEach { style.addSource(GeoJsonSource(it)) }
        map.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) dragging = true }
        map.addOnCameraIdleListener {
            dragging = false
            growWhenStill()
        }
        style.addImage("heading", images.heading())
        style.addImage("arrow-bubble", images.arrowBubble())
        style.addImage("arrow-glyph", images.arrowGlyph())
        // Under the basemap's labels.
        val layers = style.layers
        val lastGround = layers.indexOfLast { it !is SymbolLayer && !it.id.startsWith("boundary") }
        val firstLabel = layers.drop(lastGround + 1).firstOrNull { it is SymbolLayer }?.id
        fun under(layer: Layer) = if (firstLabel != null) style.addLayerBelow(layer, firstLabel) else style.addLayer(layer)
        under(
            LineLayer("ct-lines", SRC_LINES).withProperties(
                lineColor(toColor(get("color"))),
                lineOpacity(get("opacity")),
                lineWidth(3.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("dotted"), literal(false))),
        )
        under(
            LineLayer("ct-walks", SRC_LINES).withProperties(
                lineColor(toColor(get("color"))),
                lineOpacity(get("opacity")),
                lineWidth(3.5f),
                lineDasharray(arrayOf(0f, 2f)),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("dotted"), literal(true))),
        )
        for (id in listOf(SRC_SWEEP, SRC_FADE)) {
            under(
                LineLayer(id, id).withProperties(
                    lineColor(toColor(get("color"))),
                    lineOpacity(get("opacity")),
                    lineWidth(3.5f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }
        fun over(layer: SymbolLayer) {
            layer.setProperties(iconAllowOverlap(true), iconIgnorePlacement(true))
            style.addLayer(layer)
        }
        style.addLayer(
            CircleLayer("ct-ripple", SRC_RIPPLE).withProperties(
                circleRadius(get("r")),
                circleColor(android.graphics.Color.TRANSPARENT),
                circleStrokeWidth(2f),
                circleStrokeColor(toColor(get("color"))),
                circleStrokeOpacity(get("o")),
            ),
        )
        over(SymbolLayer(LAYER_STOPS, SRC_STOPS).withProperties(iconImage(get("img")), iconSize(get("size")), symbolSortKey(get("z"))))
        over(SymbolLayer("ct-ends", SRC_ENDS).withProperties(iconImage(get("img")), iconSize(get("size"))))
        over(SymbolLayer("ct-badges", SRC_BADGES).withProperties(iconImage(get("img")), iconSize(get("size"))))
        over(
            SymbolLayer("ct-arrow-bubble", SRC_ARROWS).withProperties(
                iconImage("arrow-bubble"),
                iconSize(get("size")),
                iconAnchor(Property.ICON_ANCHOR_TOP),
                iconTranslate(arrayOf(0f, MapImages.ARROW_DROP_DP)),
                iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT),
            ),
        )
        over(
            SymbolLayer("ct-arrow-glyph", SRC_ARROWS).withProperties(
                iconImage("arrow-glyph"),
                iconSize(get("size")),
                iconRotate(get("bearing")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconTranslate(arrayOf(0f, MapImages.ARROW_GLYPH_DROP_DP)),
                iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT),
            ),
        )
        over(
            SymbolLayer("ct-labels", SRC_LABELS).withProperties(
                iconImage(get("img")),
                iconSize(get("size")),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconTranslate(arrayOf(0f, -(MapImages.STOP_SELECTED_DP / 2 + 2f))),
                iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT),
                symbolSortKey(get("z")),
            ),
        )
        over(
            SymbolLayer("ct-vehicles", SRC_VEHICLES).withProperties(
                iconImage(get("img")),
                iconOpacity(get("opacity")),
                iconSize(get("size")),
                symbolSortKey(get("z")),
            ),
        )
        // The translate can't follow a feature's size, so faded buses get their own arrows.
        fun heading(id: String, faded: Boolean) {
            val size = if (faded) FADED_VEHICLE_SIZE else 1f
            over(
                SymbolLayer(id, SRC_VEHICLES).withProperties(
                    iconImage("heading"),
                    iconRotate(get("heading")),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconTranslate(arrayOf(8f * size, 8f * size)),
                    iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT),
                    iconSize(product(literal(size), get("reveal"))),
                    iconOpacity(if (faded) FADED_VEHICLE_OPACITY else 1f),
                    symbolSortKey(get("z")),
                ).withFilter(eq(get("faded"), literal(faded))),
            )
        }
        heading("ct-heading-faded", faded = true)
        heading("ct-heading", faded = false)
    }

    private fun image(name: String, make: () -> android.graphics.Bitmap) {
        if (added.add(name)) style.addImage(name, make())
    }

    private fun source(id: String): GeoJsonSource? = style.getSourceAs(id)

    private fun hex(color: Color) = "#%06X".format(0xFFFFFF and color.toArgb())

    private fun key(line: MapLine) = line.id.ifEmpty { "${line.points.size}|${line.points.first()}|${line.points.last()}|${line.dotted}" }

    private fun lineFeature(points: List<Pair<Double, Double>>, line: MapLine, opacity: Float = line.color.alpha * 0.9f) =
        Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })).apply {
            addStringProperty("color", hex(line.color))
            addNumberProperty("opacity", opacity)
            addBooleanProperty("dotted", line.dotted)
        }

    private fun applyLines() {
        source(SRC_LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines.filterIndexed { i, _ -> i !in hidden }.map { lineFeature(it.points, it) }))
    }

    // Port of growLine.
    fun setLines(next: List<MapLine>) {
        val drawable = next.filter { it.points.size >= 2 }
        val before = lines.mapTo(HashSet(), ::key)
        val after = drawable.mapTo(HashSet(), ::key)
        val gone = lines.filter { !it.dotted && key(it) !in after }
        lines = drawable
        val fresh = drawable.indices.filter { !drawable[it].dotted && key(drawable[it]) !in before }
        if (fresh.isEmpty()) {
            if (!after.containsAll(growingKeys)) stopGrowing()
            else hidden = drawable.indices.filter { key(drawable[it]) in growingKeys }.toSet()
            return applyLines()
        }
        fadeOut(gone)
        stopGrowing()
        // A map following someone never settles, so their lines are drawn as they are.
        if (!ValueAnimator.areAnimatorsEnabled() || ambient || follow != null) return applyLines()
        val grows = fresh.map { drawable[it] }
        hidden = fresh.toSet()
        growingKeys = grows.mapTo(HashSet(), ::key)
        applyLines()
        reveal.grow(grows.map { line -> pathThrough(line.points.map { it.first }.toDoubleArray(), line.points.map { it.second }.toDoubleArray()) })
        pushMarks()
        pushVehicles()
        pendingGrow = { grow(grows) }
        // Posted, so a move started in the same update counts.
        handler.post(::growWhenStill)
    }

    private fun stopGrowing() {
        pendingGrow = null
        sweep?.cancel()
        hidden = emptySet()
        growingKeys = emptySet()
        reveal.end(System.nanoTime())
        startReveal()
    }

    private fun growWhenStill() {
        if (aiming > 0 || dragging && follow == null) return
        val grow = pendingGrow ?: return
        pendingGrow = null
        grow()
    }

    private fun grow(grows: List<MapLine>) {
        sweep = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val done = a.animatedValue as Float
                reveal.draw(done)
                startReveal()
                val parts = grows.map { it to partBetween(it.points, 0f, done) }.filter { it.second.size >= 2 }
                source(SRC_SWEEP)?.setGeoJson(FeatureCollection.fromFeatures(parts.map { (line, part) -> lineFeature(part, line) }))
            }
            doOnEnd {
                sweep = null
                hidden = emptySet()
                growingKeys = emptySet()
                applyLines()
                source(SRC_SWEEP)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                reveal.end(System.nanoTime())
                startReveal()
            }
            start()
        }
    }

    private fun fadeOut(gone: List<MapLine>) {
        fade?.cancel()
        if (gone.isEmpty() || !ValueAnimator.areAnimatorsEnabled() || ambient) return
        fade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = LEAVE_MS
            interpolator = AccelerateInterpolator()
            addUpdateListener { a ->
                val fade = a.animatedValue as Float
                source(SRC_FADE)?.setGeoJson(FeatureCollection.fromFeatures(gone.map { lineFeature(it.points, it, it.color.alpha * 0.9f * fade) }))
            }
            doOnEnd { source(SRC_FADE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
            start()
        }
    }

    private fun startReveal() {
        if (revealing) return
        revealing = true
        Choreographer.getInstance().postFrameCallback(revealFrame)
    }

    private fun stepReveal() {
        revealing = false
        val now = System.nanoTime()
        val more = reveal.moving(now)
        pushMarks(now)
        pushVehicles()
        if (more) startReveal()
    }

    fun setEnds(badges: List<MapBadge>, ends: List<MapEnd>, arrows: List<MapArrow>) {
        this.badges = badges
        this.ends = ends
        this.arrows = arrows
        pushEnds(System.nanoTime())
    }

    fun setStops(stops: List<MapStop>) {
        this.stops = stops
        pushStops(System.nanoTime())
    }

    fun setLabels(labels: List<MapLabel>) {
        this.labels = labels
        pushLabels(System.nanoTime())
    }

    private fun pushMarks(now: Long = System.nanoTime()) {
        pushStops(now)
        pushLabels(now)
        pushEnds(now)
    }

    private fun pushEnds(now: Long) {
        source(SRC_ENDS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                ends.map { end ->
                    val name = "end-${end.color.toArgb()}"
                    image(name) { images.end(end.color) }
                    Feature.fromGeometry(Point.fromLngLat(end.lon, end.lat)).apply {
                        addStringProperty("img", name)
                        addNumberProperty("size", reveal.stop(end.lat to end.lon, now))
                    }
                },
            ),
        )
        source(SRC_BADGES)?.setGeoJson(
            FeatureCollection.fromFeatures(
                badges.map { badge ->
                    val name = "badge-" + badge.routes.joinToString(",") { "${it.first}:${it.second.toArgb()}" }
                    image(name) { images.badge(badge.routes) }
                    Feature.fromGeometry(Point.fromLngLat(badge.lon, badge.lat)).apply {
                        addStringProperty("img", name)
                        addNumberProperty("size", reveal.stop(badge.lat to badge.lon, now))
                    }
                },
            ),
        )
        source(SRC_ARROWS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                arrows.map { arrow ->
                    Feature.fromGeometry(Point.fromLngLat(arrow.lon, arrow.lat)).apply {
                        addNumberProperty("bearing", arrow.bearing)
                        addNumberProperty("size", reveal.stop(arrow.lat to arrow.lon, now))
                    }
                },
            ),
        )
    }

    private fun pushStops(now: Long) {
        source(SRC_STOPS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                stops.map { stop ->
                    val name = "stop-${stop.color.toArgb()}-${stop.selected}-${stop.mode}-${stop.heart}"
                    image(name) { images.stop(stop.color, stop.selected, stop.mode, stop.heart) }
                    Feature.fromGeometry(Point.fromLngLat(stop.lon, stop.lat)).apply {
                        addStringProperty("id", stop.id)
                        addStringProperty("img", name)
                        addNumberProperty("z", if (stop.selected) 1 else 0)
                        addNumberProperty("size", if (stop.selected) 1f else reveal.stop(stop.lat to stop.lon, now))
                    }
                },
            ),
        )
    }

    private fun pushLabels(now: Long) {
        source(SRC_LABELS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                labels.map { label ->
                    val name = "label-${label.live}-${label.text}"
                    image(name) { images.label(label.text, label.live) }
                    Feature.fromGeometry(Point.fromLngLat(label.lon, label.lat)).apply {
                        addStringProperty("img", name)
                        addNumberProperty("z", -label.rank.toDouble().coerceIn(-1e6, 1e6))
                        addNumberProperty("size", reveal.stop(label.lat to label.lon, now))
                    }
                },
            ),
        )
    }

    // Port of MapSession.ripple.
    fun ripple(lat: Double, lon: Double, color: Color, vehicleId: String?) {
        if (!ValueAnimator.areAnimatorsEnabled() || ambient) return
        ripple?.cancel()
        ripple = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RIPPLE_MS + RIPPLE_GAP_MS
            interpolator = LinearInterpolator()
            val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                val ms = (a.animatedValue as Float) * duration
                source(SRC_RIPPLE)?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        listOf(0L, RIPPLE_GAP_MS).map { delay ->
                            val p = ease.getInterpolation(((ms - delay) / RIPPLE_MS).coerceIn(0f, 1f))
                            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                                addNumberProperty("r", MapImages.STOP_SELECTED_DP / 2 * (1f + 2.4f * p))
                                addNumberProperty("o", if (ms < delay) 0f else 0.8f * (1f - p))
                                addStringProperty("color", hex(color))
                            }
                        },
                    ),
                )
            }
            doOnEnd { source(SRC_RIPPLE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
            start()
        }
        val id = vehicleId ?: return
        bounces.remove(id)?.cancel()
        bounces[id] = ValueAnimator.ofFloat(1.2f, 1f).apply {
            duration = paced(390L)
            interpolator = OvershootInterpolator(2.5f)
            addUpdateListener { pushVehicles() }
            doOnEnd {
                bounces.remove(id)
                pushVehicles()
            }
            start()
        }
    }

    fun setVehicles(list: List<MapVehicle>) {
        vehicles = list.filter { it.lat != 0.0 && it.lon != 0.0 }
        followVehicles()
    }

    fun setVehiclePaths(paths: Map<String, RoutePath>) {
        this.paths = paths
        followVehicles()
    }

    private fun followVehicles() {
        val now = System.currentTimeMillis()
        tracks.keys.retainAll(vehicles.mapTo(HashSet()) { it.id })
        for (v in vehicles) {
            val bus = v.vehicle
            val tripId = bus?.tripId
            val path = tripId?.let(paths::get)
            val fixMs = bus?.let { runCatching { OffsetDateTime.parse(it.timestamp).toInstant().toEpochMilli() }.getOrNull() }
            if (bus == null || path == null || fixMs == null || untracked[v.id] == tripId || !ValueAnimator.areAnimatorsEnabled()) {
                tracks.remove(v.id)
                continue
            }
            val track = tracks[v.id]?.takeIf { it.path === path } ?: VehicleTrack(path)
            if (track.fix(v.lat, v.lon, fixMs, bus.speed, v.heading, now)) {
                tracks[v.id] = track
            } else {
                tracks.remove(v.id)
                untracked[v.id] = tripId
            }
        }
        pushVehicles()
        move()
    }

    private fun move() {
        if (moving || ambient || tracks.isEmpty()) return
        moving = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stepVehicles() {
        moving = false
        if (ambient || tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        for (track in tracks.values) track.step(now)
        val step = map.projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 0.0) * MIN_STEP_PX
        if (tracks.any { (id, track) -> moved(id, track, step) }) pushVehicles()
        // Until the fastest bus has moved far enough to see.
        val fastest = tracks.values.maxOf { it.v }
        moving = true
        val delay = if (fastest <= 0) MAX_IDLE_MS else (step / fastest * 1000).toLong().coerceAtMost(MAX_IDLE_MS)
        Choreographer.getInstance().postFrameCallbackDelayed(frame, delay)
    }

    private fun moved(id: String, track: VehicleTrack, step: Double): Boolean {
        val (at, heading) = drawn[id] ?: return true
        val (lat, lon) = track.position
        return haversineMeters(at.first, at.second, lat, lon) >= step || abs((track.heading - heading + 540) % 360 - 180) >= 1
    }

    private fun pushVehicles() {
        drawn.clear()
        val shown = reveal.vehicle(System.nanoTime())
        source(SRC_VEHICLES)?.setGeoJson(
            FeatureCollection.fromFeatures(
                vehicles.sortedWith(compareBy({ !it.faded }, { -it.lat })).mapIndexed { i, v ->
                    val name = "veh-${v.color.toArgb()}-${v.name}"
                    image(name) { images.vehicle(v.color, v.name) }
                    val track = tracks[v.id]?.takeIf { !ambient }
                    val at = track?.position ?: (v.lat to v.lon)
                    val heading = track?.heading ?: v.heading
                    drawn[v.id] = at to heading
                    Feature.fromGeometry(Point.fromLngLat(at.second, at.first)).apply {
                        addStringProperty("img", name)
                        addNumberProperty("heading", heading)
                        addNumberProperty("z", i)
                        addBooleanProperty("faded", v.faded)
                        val scale = shown * ((bounces[v.id]?.animatedValue as Float?) ?: 1f)
                        addNumberProperty("opacity", if (v.faded) FADED_VEHICLE_OPACITY else 1f)
                        addNumberProperty("size", (if (v.faded) FADED_VEHICLE_SIZE else 1f) * scale)
                        addNumberProperty("reveal", scale)
                    }
                },
            ),
        )
    }

    fun setMe(fix: Fix?) {
        me = fix
        glide.to(fix, follow != null && !ambient && ValueAnimator.areAnimatorsEnabled())
    }

    fun setFollow(camera: MapFollow?) {
        if (camera == follow) return
        follow = camera
        applyFollow()
    }

    // Fixes are pushed in: no location engine or permission needed.
    @SuppressLint("MissingPermission")
    private fun applyMe(fix: Fix? = me) {
        val component = map.locationComponent
        if (fix == null) {
            if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
            return
        }
        if (!component.isLocationComponentActivated) {
            val options = LocationComponentOptions.builder(context)
                .accuracyAlpha(0.15f)
                .pulseEnabled(false)
                .enableStaleState(false)
                .build()
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .locationComponentOptions(options)
                    .useDefaultLocationEngine(false)
                    .build(),
            )
            component.renderMode = RenderMode.NORMAL
            component.cameraMode = CameraMode.NONE
            component.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() {
                    if (follow != null) onFollowLost()
                }

                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
        }
        val first = !component.isLocationComponentEnabled
        // Enabling replays the last location, leaving the next update no time to animate.
        if (first) component.isLocationComponentEnabled = true
        component.forceLocationUpdate(
            Location("app").apply {
                latitude = fix.lat
                longitude = fix.lon
                accuracy = fix.accuracyMeters
                fix.bearing?.let { bearing = it }
                time = System.currentTimeMillis()
            },
        )
        if (first) applyFollow()
    }

    @SuppressLint("MissingPermission")
    private fun applyFollow() {
        val camera = follow
        val component = map.locationComponent
        if (!component.isLocationComponentActivated || !component.isLocationComponentEnabled) return
        if (camera == null) {
            followMove++
            settling = false
            followed = null
            if (component.cameraMode == CameraMode.NONE && component.renderMode == RenderMode.NORMAL) return
            // Otherwise its own animations keep moving the camera.
            component.cancelZoomWhileTrackingAnimation()
            component.cancelTiltWhileTrackingAnimation()
            component.cancelPaddingWhileTrackingAnimation()
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.NORMAL
            val flat = CameraPosition.Builder(map.cameraPosition).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
            map.easeCamera(CameraUpdateFactory.newCameraPosition(flat), paced(435))
            return
        }
        component.renderMode = RenderMode.GPS
        if (settling) return
        val top = map.height * FOLLOW_TOP_SHARE
        if (component.cameraMode == CameraMode.TRACKING_GPS) {
            if (camera == followed) return
            followed = camera
            component.zoomWhileTracking(camera.zoom, MOVE_MS.toLong())
            component.tiltWhileTracking(camera.tilt, MOVE_MS.toLong())
            component.paddingWhileTracking(doubleArrayOf(0.0, top, 0.0, 0.0), MOVE_MS.toLong())
            return
        }
        val fix = component.lastKnownLocation ?: return
        val move = ++followMove
        settling = true
        // MapLibre's own tracking transition drops padding changes made while it runs.
        val target = CameraPosition.Builder()
            .target(LatLng(fix.latitude, fix.longitude))
            .zoom(camera.zoom)
            .tilt(camera.tilt)
            .bearing(fix.bearing.toDouble())
            .padding(0.0, top, 0.0, 0.0)
            .build()
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(target),
            520,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    if (move != followMove) return
                    component.setCameraMode(
                        CameraMode.TRACKING_GPS,
                        object : OnLocationCameraTransitionListener {
                            override fun onLocationCameraTransitionFinished(cameraMode: Int) = settled(move, camera)

                            override fun onLocationCameraTransitionCanceled(cameraMode: Int) = settled(move, null)
                        },
                    )
                }

                override fun onCancel() {
                    if (move != followMove) return
                    settling = false
                    onFollowLost()
                }
            },
        )
    }

    private fun settled(move: Int, camera: MapFollow?) {
        if (move != followMove) return
        settling = false
        followed = camera
        applyFollow()
    }

    /** Mostly black, and the buses hold still. */
    fun setAmbient(on: Boolean) {
        for (layer in style.layers) {
            if (layer.id.startsWith("ct-") || layer.id.startsWith("mapbox-location")) continue
            layer.setProperties(visibility(if (on) Property.NONE else Property.VISIBLE))
        }
        if (on == ambient) return
        ambient = on
        if (on) stopGrowing()
        pushVehicles()
        move()
    }

    fun destroy() {
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().removeFrameCallback(revealFrame)
        handler.removeCallbacksAndMessages(null)
        moving = false
        pendingGrow = null
        sweep?.cancel()
        fade?.cancel()
        ripple?.cancel()
        bounces.values.forEach { it.cancel() }
    }

    fun stopAt(point: android.graphics.PointF, reach: Float): String? =
        map.queryRenderedFeatures(RectF(point.x - reach, point.y - reach, point.x + reach, point.y + reach), LAYER_STOPS)
            .firstOrNull()?.getStringProperty("id")

    fun move(camera: CameraPosition, animate: Boolean) {
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (!animate) return map.moveCamera(update)
        aiming++
        var waiting = true
        val done = {
            if (waiting) {
                waiting = false
                aiming--
                growWhenStill()
            }
        }
        map.easeCamera(
            update,
            MOVE_MS,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() = done()

                override fun onCancel() = done()
            },
        )
        // Lines start drawing halfway through the move.
        handler.postDelayed(done, MOVE_MS / 2L)
    }

    // A fresh MapView draws nothing until its first idle frame.
    fun waitForFirstFrame(view: MapView) {
        aiming++
        view.addOnDidBecomeIdleListener(object : MapView.OnDidBecomeIdleListener {
            override fun onDidBecomeIdle() {
                view.removeOnDidBecomeIdleListener(this)
                aiming--
                growWhenStill()
            }
        })
    }

    fun fit(frame: MapFrame.Fit, width: Int, height: Int, animate: Boolean) {
        val lats = frame.points.map { it.first }
        val lons = frame.points.map { it.second }
        val side = (width * frame.side).toInt()
        val top = (height * frame.top).toInt()
        val bottom = (height * frame.bottom).toInt()
        val flat = CameraPosition.Builder().bearing(0.0).tilt(0.0).padding(side.toDouble(), top.toDouble(), side.toDouble(), bottom.toDouble())
        if (lats.max() - lats.min() < 1e-6 && lons.max() - lons.min() < 1e-6) {
            move(flat.target(LatLng(lats.first(), lons.first())).zoom(FIT_MAX_ZOOM).build(), animate)
            return
        }
        val bounds = LatLngBounds.from(lats.max(), lons.max(), lats.min(), lons.min())
        val fitted = map.getCameraForLatLngBounds(bounds, intArrayOf(side, top, side, bottom), 0.0, 0.0) ?: return
        move(flat.target(fitted.target).zoom(min(fitted.zoom, FIT_MAX_ZOOM)).build(), animate)
    }
}

@Composable
fun rememberUserLocation(): State<Fix?> {
    val context = LocalContext.current
    val updates = remember(context) { UserLocation.updates(context) }
    return updates.collectAsStateWithLifecycle(null)
}
