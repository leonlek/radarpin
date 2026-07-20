package com.bydmapcam.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.location.Location
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import com.bydmapcam.R
import com.bydmapcam.data.AlertPoint
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
import kotlin.math.cos
import kotlin.math.sin

// Free vector map, no API key required. See https://openfreemap.org
// Fallback (raster OSM): build a Style from a raster source pointing at tile.openstreetmap.org.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SRC_IDLE = "src-circles-idle"
private const val SRC_ACTIVE = "src-circles-active"
private const val SRC_CENTERS = "src-centers"
private const val SRC_INFO = "src-info"
private const val SRC_ME = "src-me"

// Follow-camera glide duration ≈ the GPS update interval, so the map moves continuously
// between fixes instead of hopping. Linear easing (see easeCamera(..., false)).
private const val FOLLOW_ANIM_MS = 1000L

@Composable
fun MapLibreMap(
    points: List<AlertPoint>,
    location: Location?,
    activeIds: Set<Long>,
    infoActiveIds: Set<Long>,
    recenterTick: Int,
    onMapLongClick: (lat: Double, lng: Double) -> Unit,
    onMarkerClick: (id: Long) -> Unit,
    focus: Pair<Double, Double>?,
    headingUp: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var firstFix by remember { mutableStateOf(true) }
    // Camera follows the car until the user pans; the locate button re-enables it.
    var followMode by remember { mutableStateOf(true) }
    var prevLoc by remember { mutableStateOf<Location?>(null) }
    val meAnimator = remember { ValueAnimator.ofFloat(0f, 1f).apply { interpolator = LinearInterpolator() } }

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
            meAnimator.cancel()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        if (map == null) {
            mv.getMapAsync { m ->
                map = m
                // A user pan/zoom gesture turns off auto-follow.
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        followMode = false
                    }
                }
                // Long-press anywhere to add a point at that map location.
                m.addOnMapLongClickListener { latLng ->
                    onMapLongClick(latLng.latitude, latLng.longitude)
                    true
                }
                // Tap a marker to focus it + show info.
                m.addOnMapClickListener { latLng ->
                    val screen = m.projection.toScreenLocation(latLng)
                    val box = RectF(screen.x - 22f, screen.y - 22f, screen.x + 22f, screen.y + 22f)
                    val hitId = m.queryRenderedFeatures(box, "lyr-markers")
                        .firstOrNull()?.getNumberProperty("id")?.toLong()
                    if (hitId != null) {
                        onMarkerClick(hitId)
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
    LaunchedEffect(points, activeIds, style) {
        val s = style ?: return@LaunchedEffect
        updatePointSources(s, points, activeIds)
    }

    // INFO pop: enlarged icon+name for INFO points currently within 100 m (small, updates ~per second).
    LaunchedEffect(infoActiveIds, points, style) {
        val s = style ?: return@LaunchedEffect
        updateInfoSource(s, points, infoActiveIds)
    }

    // Me arrow + camera: interpolate between GPS fixes at ~60fps so the arrow and the map glide
    // together (arrow stays glued to centre) instead of hopping once per fix.
    LaunchedEffect(location, style) {
        val s = style ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val from = prevLoc
        prevLoc = loc
        val toBearing = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0

        if (from == null) {
            updateMeSource(s, loc.latitude, loc.longitude, toBearing)
            if (firstFix) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
                firstFix = false
            }
            return@LaunchedEffect
        }

        val fromLat = from.latitude
        val fromLng = from.longitude
        val fromBearing = if (from.hasBearing()) from.bearing.toDouble() else toBearing

        meAnimator.cancel()
        meAnimator.duration = FOLLOW_ANIM_MS
        meAnimator.removeAllUpdateListeners()
        meAnimator.addUpdateListener { anim ->
            val t = anim.animatedFraction.toDouble()
            val lat = fromLat + (loc.latitude - fromLat) * t
            val lng = fromLng + (loc.longitude - fromLng) * t
            val bearing = lerpAngle(fromBearing, toBearing, t)
            updateMeSource(s, lat, lng, bearing)
            if (followMode) {
                if (headingUp) {
                    val cam = CameraPosition.Builder(m.cameraPosition)
                        .target(LatLng(lat, lng))
                        .bearing(bearing)
                        .build()
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
                } else {
                    m.moveCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lng)))
                }
            }
        }
        meAnimator.start()
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
}

private fun setupLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_IDLE))
    style.addLayer(
        FillLayer("lyr-idle-fill", SRC_IDLE).withProperties(
            PropertyFactory.fillColor(android.graphics.Color.parseColor("#1E88E5")),
            PropertyFactory.fillOpacity(0.15f)
        )
    )
    style.addLayer(
        LineLayer("lyr-idle-line", SRC_IDLE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.parseColor("#1E88E5")),
            PropertyFactory.lineWidth(1.5f)
        )
    )

    style.addSource(GeoJsonSource(SRC_ACTIVE))
    style.addLayer(
        FillLayer("lyr-active-fill", SRC_ACTIVE).withProperties(
            PropertyFactory.fillColor(android.graphics.Color.parseColor("#E53935")),
            PropertyFactory.fillOpacity(0.28f)
        )
    )
    style.addLayer(
        LineLayer("lyr-active-line", SRC_ACTIVE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.parseColor("#E53935")),
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

    // INFO pop: enlarged icon + name for INFO points currently within ~100 m.
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
            PropertyFactory.iconSize(1.7f),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(15f),
            PropertyFactory.textColor(android.graphics.Color.parseColor("#0D47A1")),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textAllowOverlap(true)
        )
    )

    style.addSource(GeoJsonSource(SRC_ME))
    style.addLayer(
        SymbolLayer("lyr-me", SRC_ME).withProperties(
            PropertyFactory.iconImage("me_arrow"),
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
    style.addImage("me_arrow", drawableToBitmap(context, R.drawable.ic_me_arrow))
}

private fun updatePointSources(
    style: Style,
    points: List<AlertPoint>,
    activeIds: Set<Long>
) {
    val idle = ArrayList<Feature>()
    val active = ArrayList<Feature>()
    val centers = ArrayList<Feature>()
    for (p in points) {
        // Standard-alert points get a radius circle; INFO and non-alerting points do not.
        if (p.alertEnabled && !p.infoMode) {
            val poly = Feature.fromGeometry(circlePolygon(p.lat, p.lng, p.radiusM.toDouble()))
            if (p.id in activeIds) active.add(poly) else idle.add(poly)
        }
        centers.add(
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty("type", p.type.name)
                addStringProperty("name", p.name)
                addNumberProperty("id", p.id)
            }
        )
    }
    style.getSourceAs<GeoJsonSource>(SRC_IDLE)?.setGeoJson(FeatureCollection.fromFeatures(idle))
    style.getSourceAs<GeoJsonSource>(SRC_ACTIVE)?.setGeoJson(FeatureCollection.fromFeatures(active))
    style.getSourceAs<GeoJsonSource>(SRC_CENTERS)?.setGeoJson(FeatureCollection.fromFeatures(centers))
}

private fun updateMeSource(style: Style, lat: Double, lng: Double, bearing: Double) {
    val meFeature = Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
        addNumberProperty("bearing", bearing)
    }
    style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(meFeature)
}

private fun updateInfoSource(style: Style, points: List<AlertPoint>, infoActiveIds: Set<Long>) {
    val feats = points
        .filter { it.infoMode && it.id in infoActiveIds }
        .map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty("type", p.type.name)
                addStringProperty("name", p.name)
            }
        }
    style.getSourceAs<GeoJsonSource>(SRC_INFO)?.setGeoJson(FeatureCollection.fromFeatures(feats))
}

/** Shortest-path interpolation between two bearings (degrees). */
private fun lerpAngle(from: Double, to: Double, t: Double): Double {
    val diff = ((to - from + 540.0) % 360.0) - 180.0
    return (from + diff * t + 360.0) % 360.0
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
