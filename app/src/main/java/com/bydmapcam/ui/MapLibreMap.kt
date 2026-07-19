package com.bydmapcam.ui

import android.location.Location
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
import androidx.lifecycle.LifecycleEventObserver
import com.bydmapcam.data.AlertPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
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
private const val SRC_ME = "src-me"

@Composable
fun MapLibreMap(
    points: List<AlertPoint>,
    location: Location?,
    activeIds: Set<Long>,
    recenterTick: Int,
    onMapLongClick: (lat: Double, lng: Double) -> Unit,
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                    setupLayers(loaded)
                    style = loaded
                }
            }
        }
    }

    LaunchedEffect(points, activeIds, location, style) {
        val s = style ?: return@LaunchedEffect
        updateSources(s, points, activeIds, location)

        val m = map
        val loc = location
        if (m != null && loc != null) {
            val target = LatLng(loc.latitude, loc.longitude)
            when {
                firstFix -> {
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
                    firstFix = false
                }
                followMode -> m.animateCamera(CameraUpdateFactory.newLatLng(target))
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
        CircleLayer("lyr-centers", SRC_CENTERS).withProperties(
            // Colour each dot by point type (property "type").
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("type"),
                    Expression.literal("SPEED_CAMERA"), Expression.color(android.graphics.Color.parseColor("#E53935")),
                    Expression.literal("EV_STATION"), Expression.color(android.graphics.Color.parseColor("#00C853")),
                    Expression.color(android.graphics.Color.parseColor("#FB8C00")) // POI / default
                )
            ),
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(1.8f)
        )
    )

    style.addSource(GeoJsonSource(SRC_ME))
    style.addLayer(
        CircleLayer("lyr-me", SRC_ME).withProperties(
            PropertyFactory.circleColor(android.graphics.Color.parseColor("#2962FF")),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f)
        )
    )
}

private fun updateSources(
    style: Style,
    points: List<AlertPoint>,
    activeIds: Set<Long>,
    location: Location?
) {
    val idle = ArrayList<Feature>()
    val active = ArrayList<Feature>()
    val centers = ArrayList<Feature>()
    for (p in points) {
        // Only alerting points get a radius circle; every point still gets a center dot.
        if (p.alertEnabled) {
            val poly = Feature.fromGeometry(circlePolygon(p.lat, p.lng, p.radiusM.toDouble()))
            if (p.id in activeIds) active.add(poly) else idle.add(poly)
        }
        centers.add(
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty("type", p.type.name)
            }
        )
    }
    style.getSourceAs<GeoJsonSource>(SRC_IDLE)?.setGeoJson(FeatureCollection.fromFeatures(idle))
    style.getSourceAs<GeoJsonSource>(SRC_ACTIVE)?.setGeoJson(FeatureCollection.fromFeatures(active))
    style.getSourceAs<GeoJsonSource>(SRC_CENTERS)?.setGeoJson(FeatureCollection.fromFeatures(centers))
    location?.let {
        style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(Point.fromLngLat(it.longitude, it.latitude))
    }
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
