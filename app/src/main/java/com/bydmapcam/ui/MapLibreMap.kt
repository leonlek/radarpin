package com.bydmapcam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.bydmapcam.data.PointType
import com.bydmapcam.location.AppState
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
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

// Free vector map, no API key required. See https://openfreemap.org
// Fallback (raster OSM): build a Style from a raster source pointing at tile.openstreetmap.org.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SRC_ACTIVE = "src-circles-active"
private const val SRC_CENTERS = "src-centers"
private const val SRC_INFO = "src-info"
private const val SRC_PING = "src-ping"
private const val SRC_ME = "src-me"

// Follow-camera glide duration ≈ the GPS update interval, so the map moves continuously
// between fixes instead of hopping. Linear easing (see easeCamera(..., false)).
private const val FOLLOW_ANIM_MS = 1000

/** How far off a point a tap can land and still count — a fingertip, not a mouse pointer. */
private const val TAP_SLOP_DP = 26f

// Entrance timings: short enough to be over before it can distract, slow enough to be seen.
private const val POP_SCALE_FROM = 1.15f
/** Just enough overshoot to feel alive; more looks like the icon is wobbling. */
private const val POP_DAMPING = 0.62f
private const val POP_SCALE_TO = 2.0f
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // The map's listeners are registered once, when the map is created, so a plain lambda would
    // freeze that first composition's captures forever — including an empty points list, which is
    // why tapping a point saved after launch did nothing at all.
    val onMarkerClickNow by rememberUpdatedState(onMarkerClick)
    val onMapLongClickNow by rememberUpdatedState(onMapLongClick)
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
    val popScale = remember { Animatable(POP_SCALE_TO) }
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
                    val screen = m.projection.toScreenLocation(latLng)
                    val slop = TAP_SLOP_DP * context.resources.displayMetrics.density
                    val box = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
                    val hitId = m.queryRenderedFeatures(box, "lyr-markers", "lyr-info")
                        .firstOrNull()?.getNumberProperty("id")?.toLong()
                    if (hitId != null) {
                        onMarkerClickNow(hitId)
                        true
                    } else {
                        false
                    }
                }
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                    addMarkerImages(loaded, context)
                    setupLayers(loaded)
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
            popScale.animateTo(
                POP_SCALE_TO,
                spring(dampingRatio = POP_DAMPING, stiffness = Spring.StiffnessMediumLow)
            ) {
                icon?.setProperties(PropertyFactory.iconSize(value))
            }
            // Land exactly on the target even if the spring was interrupted.
            icon?.setProperties(PropertyFactory.iconSize(POP_SCALE_TO))
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
            PropertyFactory.iconSize(2.0f),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(14f),
            PropertyFactory.textColor(android.graphics.Color.parseColor("#0D47A1")),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
            PropertyFactory.textLineHeight(1.25f),
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
