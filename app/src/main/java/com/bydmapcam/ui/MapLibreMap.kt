package com.bydmapcam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import com.bydmapcam.R
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.GeoPoint
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.PointType
import com.bydmapcam.data.Side
import com.bydmapcam.data.SideRule
import com.bydmapcam.location.AppState
import com.bydmapcam.parking.ParkingRules
import com.bydmapcam.parking.ParkingState
import com.bydmapcam.settings.MeIcon
import com.bydmapcam.offline.MapCamera
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Free vector map, no API key required. See https://openfreemap.org
// Fallback (raster OSM): build a Style from a raster source pointing at tile.openstreetmap.org.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SRC_ACTIVE = "src-circles-active"
private const val SRC_CENTERS = "src-centers"
private const val SRC_INFO = "src-info"
private const val SRC_PING = "src-ping"
private const val SRC_ME = "src-me"
private const val SRC_PARKED_CAR = "src-parked-car"
private const val SRC_PARKING = "src-parking"
private const val SRC_PARKING_LABEL = "src-parking-label"
private const val SRC_DRAFT = "src-draft"
private const val SRC_DRAFT_PTS = "src-draft-pts"

/**
 * How far off the middle of the road each kerb is drawn — a real distance, so it straddles the road
 * at every zoom. Nine metres rather than a kerb's honest four or five: the point of the pair is to
 * frame the road, and a line that lands on the tarmac of a four-lane street reads as "this road",
 * not "this side of it". On a narrow soi it sits a little past the kerb instead, which costs
 * nothing — there is no other road there to confuse it with.
 */
private const val KERB_OFFSET_M = 9.0

/** The label sits this much beyond its kerb, clear of the line it belongs to and of its opposite. */
private const val LABEL_OFFSET_M = 16.0

/** Below this the kerbs are hair-thin clutter over a whole district; a parked car is never here. */
private const val PARKING_MIN_ZOOM = 14f

/**
 * The green badge is the answer, so it comes in first; the other kerb's day-word waits until the
 * two labels are far enough apart on screen to both be read. They are twelve metres either side of
 * the road, which is a hair under one badge-height at this zoom and a comfortable gap by the next.
 */
private const val PARKING_BADGE_MIN_ZOOM = 15f
private const val PARKING_LABEL_MIN_ZOOM = 16.5f

private const val BADGE_IMAGE = "parking_badge"

// Follow-camera glide duration ≈ the GPS update interval, so the map moves continuously
// between fixes instead of hopping. Linear easing (see easeCamera(..., false)).
private const val FOLLOW_ANIM_MS = 1000

/** How far off a point a tap can land and still count — a fingertip, not a mouse pointer. */
private const val TAP_SLOP_DP = 26f

// Entrance timings: short enough to be over before it can distract, slow enough to be seen.
private const val POP_SCALE_FROM = 1.15f
/** Just enough overshoot to feel alive; more looks like the icon is wobbling. */
private const val POP_DAMPING = 0.62f
/** How big it gets at the moment of opening — the part meant to be caught, not read. */
private const val POP_SCALE_PEAK = 3.0f
/** Where it settles once seen, and stays until the point is behind you. */
private const val POP_SCALE_REST = 2.0f
/** Long enough at the peak to register as an event; longer and it is just a big icon. */
private const val POP_HOLD_MS = 800L
private const val POP_SETTLE_MS = 420
/** Label gap as a fraction of icon size — 1.2 em at the old fixed size of 2.0. */
private const val POP_TEXT_OFFSET_PER_SCALE = 0.6f
private const val POP_FADE_IN_MS = 180
private const val POP_FADE_OUT_MS = 200
private const val PING_MS = 700
private const val PING_FROM_PX = 8f
private const val PING_TO_PX = 78f
private const val PING_ALPHA = 0.95f
private const val PING_FILL_ALPHA = 0.3f

/** A point currently popped open on the map, with the distance its label is counting down. */
private data class InfoPop(val point: AlertPoint, val distanceM: Int?)

@Composable
fun MapLibreMap(
    points: List<AlertPoint>,
    location: Location?,
    activeIds: Set<Long>,
    infoActiveIds: Set<Long>,
    distances: Map<Long, Int>,
    recenterTick: Int,
    zoomInTick: Int,
    zoomOutTick: Int,
    onMapLongClick: (lat: Double, lng: Double) -> Unit,
    onMarkerClick: (id: Long) -> Unit,
    focus: Pair<Double, Double>?,
    headingUp: Boolean,
    meIcon: MeIcon,
    /** Parking blocks to paint; empty when the driver has turned the kerb lines off. */
    parkingBlocks: List<ParkingBlock> = emptyList(),
    /** The instant the kerb colours describe. Only changes when the answer can have changed. */
    parkingAt: Long = 0L,
    /** A block being drawn right now, drawn as a draft over the saved ones. */
    draft: ParkingDraft? = null,
    /** Where the car was left standing, so the walk back has something to aim at. */
    parkedSpot: GeoPoint? = null,
    /**
     * Consumes a plain tap while drawing; true means "handled, don't treat it as a normal tap".
     * [trace] is the stretch of road the tap landed on, read off the basemap and ending at the
     * tapped spot — empty when nothing snapped, in which case the raw tap is all there is.
     */
    onMapTap: (tap: GeoPoint, trace: List<GeoPoint>) -> Boolean = { _, _ -> false },
    onParkingClick: (id: Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // The map's listeners are registered once, when the map is created, so a plain lambda would
    // freeze that first composition's captures forever — including an empty points list, which is
    // why tapping a point saved after launch did nothing at all.
    val onMarkerClickNow by rememberUpdatedState(onMarkerClick)
    val onMapLongClickNow by rememberUpdatedState(onMapLongClick)
    val onMapTapNow by rememberUpdatedState(onMapTap)
    val onParkingClickNow by rememberUpdatedState(onParkingClick)
    val draftNow by rememberUpdatedState(draft)
    // Filled once the style is up. Held as a State object, not a captured value, because the click
    // listener is registered before the style has finished loading.
    val roadLayers = remember { mutableStateOf<List<String>>(emptyList()) }
    val density = LocalDensity.current
    val navBarInsetPx = WindowInsets.navigationBars.getBottom(density)
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var firstFix by remember { mutableStateOf(true) }
    // Camera follows the car until the user pans; the locate button re-enables it.
    var followMode by remember { mutableStateOf(true) }
    // Entrance animation state for the pop, plus which ids have already played it.
    val popScale = remember { Animatable(POP_SCALE_REST) }
    val popFade = remember { Animatable(1f) }
    val pingProgress = remember { Animatable(1f) }
    val inForeground by AppState.inForeground.collectAsState()

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier) {
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { mv ->
        if (map == null) {
            mv.getMapAsync { m ->
                map = m
                // MapLibre's compass sits in the top-right corner — right under our settings gear —
                // and pops up any time heading-up rotates the map. The me-arrow already shows which
                // way the car faces, so drop it rather than stack two controls on the same spot.
                m.uiSettings.isCompassEnabled = false
                // A user pan/zoom gesture turns off auto-follow.
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        followMode = false
                    }
                }
                // Publish the visible region so the offline downloader can grab "what's on screen".
                m.addOnCameraIdleListener {
                    MapCamera.bounds = m.projection.visibleRegion.latLngBounds
                    MapCamera.zoom = m.cameraPosition.zoom
                }
                // Long-press anywhere to add a point at that map location.
                m.addOnMapLongClickListener { latLng ->
                    onMapLongClickNow(latLng.latitude, latLng.longitude)
                    true
                }
                // Tap a point to focus it + show info. The hit box is in *pixels*, so it has to be
                // derived from the density — a fixed 22 px was barely 7 dp on a phone, which is why
                // markers were so fiddly to hit. INFO points are queried too; they're a separate
                // layer and used to ignore taps entirely.
                m.addOnMapClickListener { latLng ->
                    // While a block is being drawn every tap belongs to the drawing, including one
                    // that lands on a marker — the map is a canvas for that stretch. The tap also
                    // arrives with the stretch of road it landed on, traced off the basemap's own
                    // geometry, so a bend needs no more taps than a straight.
                    val tap = GeoPoint(latLng.latitude, latLng.longitude)
                    val trace = roadTrace(
                        map = m,
                        layers = roadLayers.value,
                        tap = latLng,
                        from = draftNow?.path?.lastOrNull(),
                        density = context.resources.displayMetrics.density
                    )
                    if (onMapTapNow(tap, trace)) {
                        return@addOnMapClickListener true
                    }
                    val screen = m.projection.toScreenLocation(latLng)
                    val slop = TAP_SLOP_DP * context.resources.displayMetrics.density
                    val box = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
                    val hitId = m.queryRenderedFeatures(box, "lyr-markers", "lyr-info")
                        .firstOrNull()?.getNumberProperty("id")?.toLong()
                    if (hitId != null) {
                        onMarkerClickNow(hitId)
                        return@addOnMapClickListener true
                    }
                    // Points win ties: a kerb line is long and easy to hit by accident, a marker is
                    // small and deliberate.
                    val blockId = m.queryRenderedFeatures(box, "lyr-parking-left", "lyr-parking-right")
                        .firstOrNull()?.getNumberProperty("id")?.toLong()
                    if (blockId != null) {
                        onParkingClickNow(blockId)
                        true
                    } else {
                        false
                    }
                }
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                    addMarkerImages(loaded, context)
                    setupLayers(loaded)
                    roadLayers.value = roadLayerIds(loaded)
                    style = loaded
                }
            }
        }
    }

    // Circles + markers: rebuild ONLY when the saved points / active set change — not on every GPS
    // tick — so the markers & labels don't re-render and flicker while the car moves.
    LaunchedEffect(points, activeIds, style, inForeground) {
        val s = style ?: return@LaunchedEffect
        if (!inForeground) return@LaunchedEffect
        updatePointSources(s, points, activeIds)
    }

    // Parking kerbs. Rebuilt only when the blocks change or [parkingAt] moves — which is midnight
    // for most streets, so this normally runs once a day and then never again.
    LaunchedEffect(parkingBlocks, parkingAt, style, inForeground) {
        val s = style ?: return@LaunchedEffect
        if (!inForeground) return@LaunchedEffect
        updateParkingSource(s, parkingBlocks, parkingAt)
    }

    // Where the car is parked. One feature that changes twice a day at most.
    LaunchedEffect(parkedSpot, style) {
        val s = style ?: return@LaunchedEffect
        val feature = parkedSpot?.let {
            Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat))
        }
        s.getSourceAs<GeoJsonSource>(SRC_PARKED_CAR)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(feature))
        )
    }

    // The block being traced. Redrawn on every tap, but a draft is a handful of vertices and the
    // driver is stopped while drawing one.
    LaunchedEffect(draft, style) {
        val s = style ?: return@LaunchedEffect
        updateDraftSources(s, draft)
    }

    // INFO pop: enlarged icon + details for points currently within ~200 m. The distance is rounded
    // to 10 m so the source is only rewritten when the label text really changes, not every fix.
    val popped = remember(points, infoActiveIds, distances) {
        points.filter { it.id in infoActiveIds }
            .map { InfoPop(it, distances[it.id]?.let { d -> (d / 10) * 10 }) }
    }
    // Which points are open drives the animation; the label text drives only the source. Keeping
    // them on separate keys matters: the distance ticks down every 10 m, and if that restarted the
    // effect it would cancel the entrance half-way and leave the icon frozen at whatever size the
    // spring had reached.
    val poppedIds = remember(popped) { popped.map { it.point.id }.toSet() }

    LaunchedEffect(popped, style, inForeground) {
        val s = style ?: return@LaunchedEffect
        if (!inForeground || popped.isEmpty()) return@LaunchedEffect
        updateInfoSource(s, popped)
    }

    LaunchedEffect(poppedIds, style, inForeground) {
        val s = style ?: return@LaunchedEffect
        if (!inForeground) return@LaunchedEffect
        val icon = s.getLayerAs<SymbolLayer>("lyr-info")
        val ping = s.getLayerAs<CircleLayer>("lyr-ping")

        if (poppedIds.isEmpty()) {
            // Fade the label and icon out before dropping the features, so it doesn't blink away.
            popFade.animateTo(0f, tween(POP_FADE_OUT_MS)) {
                icon?.setProperties(
                    PropertyFactory.iconOpacity(value),
                    PropertyFactory.textOpacity(value)
                )
            }
            updateInfoSource(s, emptyList())
            updatePingSource(s, emptyList())
            ping?.setProperties(
                PropertyFactory.circleStrokeOpacity(0f),
                PropertyFactory.circleOpacity(0f)
            )
            return@LaunchedEffect
        }

        // Entrance: the icon springs up past its size and settles, label fading in with it, while a
        // single ring sweeps outward. Both are one-shot — nothing keeps moving in the driver's
        // peripheral vision, and nothing costs a frame once it's done.
        updateInfoSource(s, popped)
        popFade.snapTo(0f)
        popScale.snapTo(POP_SCALE_FROM)
        launch {
            // Big first, then smaller: the size is what catches an eye that is on the road, and it
            // only has to do that once. Holding the peak for a beat is what makes it register as a
            // thing that happened rather than a flicker; settling back afterwards leaves the label
            // readable without a permanently shouting icon in the corner of the driver's vision.
            popScale.animateTo(
                POP_SCALE_PEAK,
                spring(dampingRatio = POP_DAMPING, stiffness = Spring.StiffnessMediumLow)
            ) {
                icon?.setProperties(*popProperties(value))
            }
            icon?.setProperties(*popProperties(POP_SCALE_PEAK))
            delay(POP_HOLD_MS)
            popScale.animateTo(POP_SCALE_REST, tween(POP_SETTLE_MS, easing = FastOutSlowInEasing)) {
                icon?.setProperties(*popProperties(value))
            }
            // Land exactly on the target even if the animation was interrupted.
            icon?.setProperties(*popProperties(POP_SCALE_REST))
        }
        launch {
            popFade.animateTo(1f, tween(POP_FADE_IN_MS)) {
                icon?.setProperties(
                    PropertyFactory.iconOpacity(value),
                    PropertyFactory.textOpacity(value)
                )
            }
            icon?.setProperties(
                PropertyFactory.iconOpacity(1f),
                PropertyFactory.textOpacity(1f)
            )
        }
        launch {
            updatePingSource(s, popped)
            pingProgress.snapTo(0f)
            pingProgress.animateTo(1f, tween(PING_MS, easing = FastOutSlowInEasing)) {
                ping?.setProperties(
                    PropertyFactory.circleRadius(PING_FROM_PX + (PING_TO_PX - PING_FROM_PX) * value),
                    PropertyFactory.circleStrokeOpacity(PING_ALPHA * (1f - value)),
                    PropertyFactory.circleOpacity(PING_FILL_ALPHA * (1f - value))
                )
            }
            ping?.setProperties(
                PropertyFactory.circleStrokeOpacity(0f),
                PropertyFactory.circleOpacity(0f)
            )
        }
    }

    // Me arrow + camera. Skip entirely while backgrounded (map not visible) to save CPU/battery.
    // MapLibre's native linear ease (GPU) over ~1 GPS interval — light, no per-frame work.
    LaunchedEffect(location, style, inForeground) {
        val s = style ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!inForeground) return@LaunchedEffect

        val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        // While following, the arrow is the pinned overlay below — publishing the layer arrow too
        // would put a second, once-a-second-teleporting arrow on top of it.
        updateMeSource(s, loc.latitude, loc.longitude, bearing, visible = !followMode)

        val target = LatLng(loc.latitude, loc.longitude)
        when {
            firstFix -> {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
                firstFix = false
            }
            followMode && headingUp && loc.hasBearing() -> {
                val cam = CameraPosition.Builder(m.cameraPosition)
                    .target(target)
                    .bearing(bearing)
                    .build()
                m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), FOLLOW_ANIM_MS, false)
            }
            followMode -> m.easeCamera(CameraUpdateFactory.newLatLng(target), FOLLOW_ANIM_MS, false)
        }
    }

    // Keep the OpenStreetMap credit readable: MapLibre parks the logo + (i) in the bottom-left
    // corner, i.e. under the radio button and behind the system nav bar. Lift it clear of both —
    // the attribution has to stay visible, it's the licence term we ship under.
    LaunchedEffect(map, navBarInsetPx) {
        val m = map ?: return@LaunchedEffect
        val pad = with(density) { 8.dp.roundToPx() }
        val bottom = navBarInsetPx + pad
        m.uiSettings.setLogoMargins(pad, 0, 0, bottom)
        // Sits clear of the ~93 dp wide MapLibre wordmark so the two don't overlap.
        m.uiSettings.setAttributionMargins(with(density) { 104.dp.roundToPx() }, 0, 0, bottom)
    }

    // Swap the car marker when the setting changes.
    LaunchedEffect(meIcon, style) {
        style?.getLayerAs<SymbolLayer>("lyr-me")
            ?.setProperties(PropertyFactory.iconImage(meIcon.imageId))
    }

    // Zoom buttons — same one-step-per-tap as the map's own gesture, but reachable with a thumb.
    LaunchedEffect(zoomInTick) {
        if (zoomInTick > 0) map?.animateCamera(CameraUpdateFactory.zoomIn())
    }
    LaunchedEffect(zoomOutTick) {
        if (zoomOutTick > 0) map?.animateCamera(CameraUpdateFactory.zoomOut())
    }

    // Snap the map back to north-up when heading-up is turned off.
    LaunchedEffect(headingUp) {
        if (!headingUp) {
            map?.let { m ->
                val cam = CameraPosition.Builder(m.cameraPosition).bearing(0.0).build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
            }
        }
    }

    // Locate button: re-enable follow and snap back to the current location.
    LaunchedEffect(recenterTick) {
        if (recenterTick > 0) {
            followMode = true
            val m = map
            val loc = location
            if (m != null && loc != null) {
                m.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.0)
                )
            }
        }
    }

    // Focus a specific saved point (from a marker tap or the list).
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        followMode = false
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.first, f.second), 16.5))
    }

        // The camera glides to each new fix over a full second, but a map-layer marker can only
        // teleport once per fix — hence the hopping. While following, pin the arrow to the centre
        // and let the map slide underneath it (what turn-by-turn navigators do); costs nothing per
        // frame. When the user pans away, the layer arrow takes over at the real position.
        if (followMode && location != null) {
            Image(
                painter = painterResource(meIcon.res),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (meIcon == MeIcon.ARROW) 32.dp else 50.dp)
                    // Heading-up already rotates the whole map, so the arrow simply points up.
                    .rotate(if (headingUp && location.hasBearing()) 0f else location.bearing)
            )
        }
    }
}

/** Data-driven colour so one layer can serve every point type. */
private fun alertColorByType(): Expression = Expression.match(
    Expression.get("type"),
    Expression.literal(PointType.EV_STATION.name),
    Expression.literal(colorHex(PointType.EV_STATION.alertColor)),
    Expression.literal(PointType.POI.name),
    Expression.literal(colorHex(PointType.POI.alertColor)),
    Expression.literal(colorHex(PointType.SPEED_CAMERA.alertColor))
)

private fun colorHex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

private fun setupLayers(style: Style) {
    // Parking kerbs go in first so every marker, ring and label stays on top of them: they are
    // information about the street itself, not something that competes with a warning.
    setupParkingLayers(style)

    // The live ring matches its banner, so a green card never sits over a red circle.
    style.addSource(GeoJsonSource(SRC_ACTIVE))
    style.addLayer(
        FillLayer("lyr-active-fill", SRC_ACTIVE).withProperties(
            PropertyFactory.fillColor(alertColorByType()),
            PropertyFactory.fillOpacity(0.28f)
        )
    )
    style.addLayer(
        LineLayer("lyr-active-line", SRC_ACTIVE).withProperties(
            PropertyFactory.lineColor(alertColorByType()),
            PropertyFactory.lineWidth(2f)
        )
    )

    style.addSource(GeoJsonSource(SRC_CENTERS))
    style.addLayer(
        SymbolLayer("lyr-markers", SRC_CENTERS).withProperties(
            // Icon by type (m_camera / m_ev / m_poi), with the point name as a label below it.
            PropertyFactory.iconImage(
                Expression.match(
                    Expression.get("type"),
                    Expression.literal("SPEED_CAMERA"), Expression.literal("m_camera"),
                    Expression.literal("EV_STATION"), Expression.literal("m_ev"),
                    Expression.literal("m_poi") // POI / default
                )
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.9f),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(android.graphics.Color.parseColor("#212121")),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textOptional(true),
            PropertyFactory.textAllowOverlap(false)
        )
    )

    // One radar sweep out of the icon as it opens — catchable out of the corner of the eye, then
    // gone. Under the symbol layer so the icon always sits on top of it.
    style.addSource(GeoJsonSource(SRC_PING))
    style.addLayer(
        CircleLayer("lyr-ping", SRC_PING).withProperties(
            PropertyFactory.circleRadius(0f),
            // A washed-out ring reads as a rendering artefact; it needs a filled body behind the
            // stroke to be caught peripherally at a glance.
            PropertyFactory.circleColor(alertColorByType()),
            PropertyFactory.circleOpacity(0f),
            PropertyFactory.circleStrokeWidth(6f),
            PropertyFactory.circleStrokeColor(alertColorByType()),
            PropertyFactory.circleStrokeOpacity(0f)
        )
    )

    // INFO pop: enlarged icon + name for INFO points currently within ~200 m.
    style.addSource(GeoJsonSource(SRC_INFO))
    style.addLayer(
        SymbolLayer("lyr-info", SRC_INFO).withProperties(
            PropertyFactory.iconImage(
                Expression.match(
                    Expression.get("type"),
                    Expression.literal("SPEED_CAMERA"), Expression.literal("m_camera"),
                    Expression.literal("EV_STATION"), Expression.literal("m_ev"),
                    Expression.literal("m_poi")
                )
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(POP_SCALE_REST),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(14f),
            PropertyFactory.textColor(android.graphics.Color.parseColor("#0D47A1")),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, POP_TEXT_OFFSET_PER_SCALE * POP_SCALE_REST)),
            PropertyFactory.textLineHeight(1.25f),
            PropertyFactory.textAllowOverlap(true)
        )
    )

    // The parked car sits under the me-marker: when you are standing next to it the two overlap,
    // and the one that has to be readable then is the one showing where *you* are.
    style.addSource(GeoJsonSource(SRC_PARKED_CAR))
    style.addLayer(
        SymbolLayer("lyr-parked-car", SRC_PARKED_CAR).withProperties(
            PropertyFactory.iconImage(MeIcon.SEDAN.imageId),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.9f),
            PropertyFactory.iconOpacity(0.85f),
            PropertyFactory.textField("จอดไว้ที่นี่"),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(android.graphics.Color.parseColor("#37474F")),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(1.8f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAllowOverlap(true)
        )
    )

    style.addSource(GeoJsonSource(SRC_ME))
    style.addLayer(
        SymbolLayer("lyr-me", SRC_ME).withProperties(
            PropertyFactory.iconImage(MeIcon.ARROW.imageId),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(1.0f),
            // Rotate the arrow to the driving direction (property "bearing"), relative to the map.
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
        )
    )
}

/**
 * One line per kerb, drawn on geometry already pushed sideways in metres (see
 * [ParkingRules.offsetPath]), so the pair straddles the road instead of lying on it.
 *
 * A small pixel offset is added on top, fading out by the zoom where the metres are wide enough to
 * see: zoomed out to a whole district five metres is half a pixel, and two lines that land on the
 * same pixel read as one line of the wrong colour.
 */
private fun setupParkingLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_PARKING))
    Side.entries.forEach { side ->
        val id = if (side == Side.LEFT) "lyr-parking-left" else "lyr-parking-right"
        style.addLayer(
            LineLayer(id, SRC_PARKING).withProperties(
                PropertyFactory.lineColor(parkingColorByState()),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOffset(kerbSeparation(side)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineOpacity(0.9f)
            ).withFilter(Expression.eq(Expression.get("side"), Expression.literal(side.name)))
                .also { it.minZoom = PARKING_MIN_ZOOM }
        )
    }
    // Today's date, in a green badge, beside the kerb you may park on — and nothing but the
    // day-word beside the other one. A driver looking for a space needs one answer, not a
    // comparison: the badge is the answer, and its date is why, in the same units as the sign on
    // the pole. Both sit on their own source, a little further out than the kerbs themselves,
    // because two labels on lines ten metres apart land on top of each other and neither can be
    // read — and they stay horizontal rather than following the road, which a moving eye prefers.
    style.addSource(GeoJsonSource(SRC_PARKING_LABEL))
    style.addLayer(
        SymbolLayer("lyr-parking-badge", SRC_PARKING_LABEL).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor(android.graphics.Color.WHITE),
            PropertyFactory.iconImage(BADGE_IMAGE),
            // The badge grows to whatever the text needs, so "15 · คี่" and "ทุกวัน" both fit.
            PropertyFactory.iconTextFit(Property.ICON_TEXT_FIT_BOTH),
            PropertyFactory.iconTextFitPadding(arrayOf(3f, 7f, 3f, 7f)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.textAllowOverlap(true)
        ).withFilter(allowedFilter(true))
            .also { it.minZoom = PARKING_BADGE_MIN_ZOOM }
    )
    style.addLayer(
        SymbolLayer("lyr-parking-label", SRC_PARKING_LABEL).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(parkingColorByState()),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textAllowOverlap(true)
        ).withFilter(allowedFilter(false))
            .also { it.minZoom = PARKING_LABEL_MIN_ZOOM }
    )

    // The block being drawn: the traced centre line, its vertices, and — at the side-picking step —
    // both kerbs in grey with the chosen one already green, so the tap has something to answer.
    style.addSource(GeoJsonSource(SRC_DRAFT))
    Side.entries.forEach { side ->
        val id = if (side == Side.LEFT) "lyr-draft-left" else "lyr-draft-right"
        style.addLayer(
            LineLayer(id, SRC_DRAFT).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOffset(kerbSeparation(side)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            ).withFilter(Expression.eq(Expression.get("side"), Expression.literal(side.name)))
        )
    }
    style.addLayer(
        LineLayer("lyr-draft-line", SRC_DRAFT).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.parseColor("#1565C0")),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))
        ).withFilter(Expression.eq(Expression.get("side"), Expression.literal("CENTER")))
    )
    style.addSource(GeoJsonSource(SRC_DRAFT_PTS))
    style.addLayer(
        CircleLayer("lyr-draft-pts", SRC_DRAFT_PTS).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#1565C0"))
        )
    )
}

/**
 * Every line layer in the basemap that draws a road, found by asking the style rather than by
 * hard-coding names: this is an OpenMapTiles style, so roads live on the `transportation` source
 * layer, and that is a fact about the schema rather than about one stylesheet's naming. Casings are
 * skipped because they carry the same geometry as the line they sit under, and rails, footpaths and
 * ferries because you can't park a car on them.
 */
private fun roadLayerIds(style: Style): List<String> =
    style.layers.filterIsInstance<LineLayer>()
        .filter { it.sourceLayer == "transportation" }
        .map { it.id }
        .filterNot { id ->
            id.endsWith("_casing") || id.contains("rail") || id.contains("hatching") ||
                id.contains("path_pedestrian") || id.contains("ferry")
        }

/** Classes that reach a tile through a road layer but are not a street a car parks on. */
private val NON_ROAD_CLASSES = setOf("rail", "transit", "ferry", "path", "pedestrian", "bridleway")

/** How far from the tap a road may be and still be what was meant. */
private const val SNAP_MAX_M = 25.0

/** Tap slop for the road query — wider than the marker slop; a road line is a thin thing to hit. */
private const val SNAP_SLOP_DP = 28f

/** Guards against a tap at the far end of a long road turning one block into a whole avenue. */
private const val SNAP_MAX_RUN_M = 1200.0
private const val SNAP_MAX_VERTICES = 80

/** Where a position falls on a polyline: how far off it is, and how far along. */
private data class OnLine(val distanceM: Double, val index: Int, val t: Double, val point: GeoPoint)

/**
 * The road under [tap], from [from] to the tap, taken from the basemap's own geometry.
 *
 * This is what makes a curved block two taps instead of ten: rather than the straight chord between
 * them, the vertices of the road itself come along. It gives up and returns just the snapped point
 * — or nothing at all — whenever the answer would be a guess: no road near the tap, the previous
 * point on some other road, a tile boundary cutting the line short (the previous point then isn't
 * on the piece we got back), or a run so long it can't be one block.
 */
private fun roadTrace(
    map: MapLibreMap,
    layers: List<String>,
    tap: LatLng,
    from: GeoPoint?,
    density: Float
): List<GeoPoint> {
    if (layers.isEmpty()) return emptyList()
    val screen = map.projection.toScreenLocation(tap)
    val slop = SNAP_SLOP_DP * density
    val box = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
    val lines = runCatching { map.queryRenderedFeatures(box, *layers.toTypedArray()) }
        .getOrDefault(emptyList())
        .filterNot { it.getStringProperty("class") in NON_ROAD_CLASSES }
        .flatMap { linesOf(it) }
        .filter { it.size >= 2 }
    if (lines.isEmpty()) {
        snapLog("no road under the tap (${layers.size} layers queried)")
        return emptyList()
    }

    var bestLine: List<GeoPoint>? = null
    var best: OnLine? = null
    for (line in lines) {
        val hit = nearestOnLine(line, tap.latitude, tap.longitude) ?: continue
        if (best == null || hit.distanceM < best.distanceM) {
            best = hit
            bestLine = line
        }
    }
    val to = best ?: return emptyList()
    val line = bestLine ?: return emptyList()
    if (to.distanceM > SNAP_MAX_M) {
        snapLog("nearest road is ${to.distanceM.toInt()} m away — leaving the tap where it fell")
        return emptyList()
    }
    if (from == null) {
        snapLog("first point snapped ${to.distanceM.toInt()} m onto the road")
        return listOf(to.point)
    }

    val start = nearestOnLine(line, from.lat, from.lng)
    if (start == null || start.distanceM > SNAP_MAX_M) {
        snapLog("previous point is not on this road — straight segment")
        return listOf(to.point)
    }

    val forward = start.index < to.index || (start.index == to.index && start.t <= to.t)
    val between = if (forward) {
        line.subList((start.index + 1).coerceAtMost(line.size), (to.index + 1).coerceAtMost(line.size))
    } else {
        line.subList((to.index + 1).coerceAtMost(line.size), (start.index + 1).coerceAtMost(line.size))
            .reversed()
    }
    if (between.size > SNAP_MAX_VERTICES) return listOf(to.point)
    val run = ParkingRules.pathLengthM(listOf(from) + between + to.point)
    if (run > SNAP_MAX_RUN_M) return listOf(to.point)
    snapLog("followed road: +${between.size} vertices over ${run.toInt()} m")
    return between + to.point
}

/**
 * One line per tap about what the snapping decided. Cheap, quiet, and the only way to tell "the
 * road was straight here" apart from "the snap never fired" — the two look identical on screen.
 */
private fun snapLog(message: String) {
    android.util.Log.i("RadarPinSnap", message)
}

/** LineString / MultiLineString out of a queried feature, as plain points. */
private fun linesOf(feature: Feature): List<List<GeoPoint>> =
    when (val geometry = feature.geometry()) {
        is LineString -> listOf(geometry.coordinates().map { GeoPoint(it.latitude(), it.longitude()) })
        is MultiLineString -> geometry.coordinates().map { line ->
            line.map { GeoPoint(it.latitude(), it.longitude()) }
        }
        else -> emptyList()
    }

/** Closest point on a polyline, in the same local-metre frame the kerb offsets use. */
private fun nearestOnLine(line: List<GeoPoint>, lat: Double, lng: Double): OnLine? {
    if (line.size < 2) return null
    val cosLat = cos(Math.toRadians(lat))
    fun x(p: GeoPoint) = (p.lng - lng) * 111_320.0 * cosLat
    fun y(p: GeoPoint) = (p.lat - lat) * 110_540.0

    var best: OnLine? = null
    for (i in 0 until line.size - 1) {
        val ax = x(line[i])
        val ay = y(line[i])
        val bx = x(line[i + 1])
        val by = y(line[i + 1])
        val abx = bx - ax
        val aby = by - ay
        val len2 = abx * abx + aby * aby
        if (len2 == 0.0) continue
        val t = ((-ax * abx) + (-ay * aby)) / len2
        val clamped = t.coerceIn(0.0, 1.0)
        val cx = ax + abx * clamped
        val cy = ay + aby * clamped
        val d = sqrt(cx * cx + cy * cy)
        if (best == null || d < best.distanceM) {
            best = OnLine(
                distanceM = d,
                index = i,
                t = clamped,
                point = GeoPoint(
                    lat = line[i].lat + (line[i + 1].lat - line[i].lat) * clamped,
                    lng = line[i].lng + (line[i + 1].lng - line[i].lng) * clamped
                )
            )
        }
    }
    return best
}

/**
 * The icon at [scale], with its label pushed down to match.
 *
 * `text-offset` is measured in ems of the label, not in icon heights, so a growing icon walks over
 * its own name unless the offset grows with it — which is exactly what a three-times peak did the
 * first time it was tried. The ratio is the one the settled size has always used.
 */
private fun popProperties(scale: Float) = arrayOf(
    PropertyFactory.iconSize(scale),
    PropertyFactory.textOffset(arrayOf(0f, POP_TEXT_OFFSET_PER_SCALE * scale))
)

/** Splits the two kerb symbol layers: the one you can park on today, and the one you can't. */
private fun allowedFilter(allowed: Boolean): Expression {
    val isAllowed = Expression.eq(
        Expression.get("state"),
        Expression.literal(ParkingState.ALLOWED.name)
    )
    return if (allowed) isAllowed else Expression.not(isAllowed)
}

/** The green pill the date sits in. Stretched to the text by `icon-text-fit`, so one will do. */
private fun badgeBitmap(): Bitmap {
    val w = 120
    val h = 56
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ParkingState.ALLOWED.color.toInt()
    }
    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
    return bitmap
}

/**
 * The screen-space nudge that keeps the two kerbs apart while their real five metres are still
 * sub-pixel, gone by the zoom where the geometry speaks for itself.
 */
private fun kerbSeparation(side: Side): Expression {
    val sign = if (side == Side.LEFT) -1f else 1f
    return Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(PARKING_MIN_ZOOM, 5f * sign),
        Expression.stop(17f, 2f * sign)
    )
}

/** Colour straight off the state, so the map, the info card and the warning can never disagree. */
private fun parkingColorByState(): Expression = Expression.match(
    Expression.get("state"),
    Expression.literal(ParkingState.ALLOWED.name),
    Expression.literal(colorHex(ParkingState.ALLOWED.color)),
    Expression.literal(ParkingState.BANNED_NOW.name),
    Expression.literal(colorHex(ParkingState.BANNED_NOW.color)),
    Expression.literal(ParkingState.BANNED_ALWAYS.name),
    Expression.literal(colorHex(ParkingState.BANNED_ALWAYS.color)),
    Expression.literal(colorHex(ParkingState.WRONG_DAY.color))
)

private fun updateParkingSource(style: Style, blocks: List<ParkingBlock>, at: Long) {
    val feats = ArrayList<Feature>(blocks.size * 2)
    val labels = ArrayList<Feature>(blocks.size * 2)
    for (b in blocks) {
        if (b.path.size < 2) continue
        for (side in Side.entries) {
            val kerb = ParkingRules.offsetPath(b.path, side, KERB_OFFSET_M)
            val state = ParkingRules.stateOf(b, side, at)
            feats.add(
                Feature.fromGeometry(
                    LineString.fromLngLats(kerb.map { Point.fromLngLat(it.lng, it.lat) })
                ).apply {
                    addNumberProperty("id", b.id)
                    addStringProperty("side", side.name)
                    addStringProperty("state", state.name)
                }
            )
            val anchor = ParkingRules.midpoint(
                ParkingRules.offsetPath(b.path, side, LABEL_OFFSET_M)
            ) ?: continue
            labels.add(
                Feature.fromGeometry(Point.fromLngLat(anchor.lng, anchor.lat)).apply {
                    addStringProperty("state", state.name)
                    addStringProperty("label", kerbLabel(b.ruleOf(side), state, at))
                }
            )
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_PARKING)?.setGeoJson(FeatureCollection.fromFeatures(feats))
    style.getSourceAs<GeoJsonSource>(SRC_PARKING_LABEL)
        ?.setGeoJson(FeatureCollection.fromFeatures(labels))
}

/**
 * What a kerb says on the map. The one you can park on carries today's date — the same number the
 * sign on the pole is about, so there's no odd/even arithmetic to do at 30 km/h — and the others
 * carry only the word, which is enough to see that they are somebody else's day.
 */
private fun kerbLabel(rule: SideRule, state: ParkingState, at: Long): String = when {
    state != ParkingState.ALLOWED -> rule.shortLabel
    // A kerb with no alternation has no date to give; the word already says everything.
    rule == SideRule.ALWAYS -> rule.shortLabel
    else -> "${ParkingRules.dayOfMonth(at)} · ${rule.shortLabel}"
}

private fun updateDraftSources(style: Style, draft: ParkingDraft?) {
    val path = draft?.path.orEmpty()
    val feats = ArrayList<Feature>(3)
    if (path.size >= 2) {
        val line = LineString.fromLngLats(path.map { Point.fromLngLat(it.lng, it.lat) })
        feats.add(
            Feature.fromGeometry(line).apply { addStringProperty("side", "CENTER") }
        )
        // Drawn from the first two taps onward, not just at the side-picking step: the kerbs are
        // what the driver is really placing, and seeing them follow the tarmac (or fail to, on a
        // bend traced with too few points) while there is still a finger on the map is the whole
        // difference between fixing it now and discovering it afterwards.
        run {
            for (side in Side.entries) {
                val kerb = ParkingRules.offsetPath(path, side, KERB_OFFSET_M)
                feats.add(
                    Feature.fromGeometry(
                        LineString.fromLngLats(kerb.map { Point.fromLngLat(it.lng, it.lat) })
                    ).apply {
                        addStringProperty("side", side.name)
                        // Both kerbs the same neutral colour: the question is which one, and a
                        // colour difference before the answer would look like the answer.
                        addStringProperty("color", "#78909C")
                    }
                )
            }
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_DRAFT)?.setGeoJson(FeatureCollection.fromFeatures(feats))
    style.getSourceAs<GeoJsonSource>(SRC_DRAFT_PTS)?.setGeoJson(
        FeatureCollection.fromFeatures(
            path.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) }
        )
    )
}

private fun drawableToBitmap(context: Context, resId: Int): Bitmap {
    val drawable = ContextCompat.getDrawable(context, resId)!!
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bitmap
}

private fun addMarkerImages(style: Style, context: Context) {
    style.addImage(BADGE_IMAGE, badgeBitmap())
    style.addImage("m_camera", drawableToBitmap(context, R.drawable.ic_marker_camera))
    style.addImage("m_poi", drawableToBitmap(context, R.drawable.ic_marker_poi))
    style.addImage("m_ev", drawableToBitmap(context, R.drawable.ic_marker_ev))
    MeIcon.entries.forEach { style.addImage(it.imageId, drawableToBitmap(context, it.res)) }
}

private fun updatePointSources(
    style: Style,
    points: List<AlertPoint>,
    activeIds: Set<Long>
) {
    val active = ArrayList<Feature>()
    val centers = ArrayList<Feature>()
    for (p in points) {
        // A ring means "this one is warning you right now", so it appears with the alert and goes
        // with it — including the moment you drive past and the banner closes. Points that only pop
        // (POI, charger, INFO) never draw one at all.
        if (p.id in activeIds && p.alertEnabled && !p.infoMode && !p.type.popsOnly) {
            active.add(
                Feature.fromGeometry(circlePolygon(p.lat, p.lng, p.radiusM.toDouble()))
                    .apply { addStringProperty("type", p.type.name) }
            )
        }
        centers.add(
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty("type", p.type.name)
                addStringProperty("name", p.name)
                addNumberProperty("id", p.id)
            }
        )
    }
    style.getSourceAs<GeoJsonSource>(SRC_ACTIVE)?.setGeoJson(FeatureCollection.fromFeatures(active))
    style.getSourceAs<GeoJsonSource>(SRC_CENTERS)?.setGeoJson(FeatureCollection.fromFeatures(centers))
}

private fun updateMeSource(
    style: Style,
    lat: Double,
    lng: Double,
    bearing: Double,
    visible: Boolean
) {
    if (!visible) {
        style.getSourceAs<GeoJsonSource>(SRC_ME)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val meFeature = Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
        addNumberProperty("bearing", bearing)
    }
    style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(meFeature)
}

/** The ring only needs a position and a type to take its colour from. */
private fun updatePingSource(style: Style, popped: List<InfoPop>) {
    val feats = popped.map { pop ->
        Feature.fromGeometry(Point.fromLngLat(pop.point.lng, pop.point.lat)).apply {
            addStringProperty("type", pop.point.type.name)
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_PING)?.setGeoJson(FeatureCollection.fromFeatures(feats))
}

private fun updateInfoSource(style: Style, popped: List<InfoPop>) {
    val feats = popped.map { pop ->
        val p = pop.point
        Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
            addStringProperty("type", p.type.name)
            // The detail "grows out of" the icon: name, then what it is and how far, on its own line.
            addStringProperty(
                "name",
                buildString {
                    append(p.name)
                    append("\n")
                    if (p.name != p.type.label) append(p.type.label).append(" · ")
                    append(pop.distanceM ?: 0).append(" ม.")
                }
            )
            addNumberProperty("id", p.id) // so a tap on the popped icon resolves to a point
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_INFO)?.setGeoJson(FeatureCollection.fromFeatures(feats))
}

/** Approximate a circle of [radiusM] meters around a lat/lng as a GeoJSON polygon. */
private fun circlePolygon(lat: Double, lng: Double, radiusM: Double, steps: Int = 48): Polygon {
    val earth = 6_378_137.0
    val lat0 = Math.toRadians(lat)
    val ring = ArrayList<Point>(steps + 1)
    for (i in 0..steps) {
        val theta = 2.0 * Math.PI * i / steps
        val dx = radiusM * cos(theta)
        val dy = radiusM * sin(theta)
        val dLng = Math.toDegrees(dx / (earth * cos(lat0)))
        val dLat = Math.toDegrees(dy / earth)
        ring.add(Point.fromLngLat(lng + dLng, lat + dLat))
    }
    return Polygon.fromLngLats(listOf(ring))
}
