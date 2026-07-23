package com.bydmapcam.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bydmapcam.R
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.Trip
import com.bydmapcam.data.avgKmPerPercent
import com.bydmapcam.location.LocationBus
import com.bydmapcam.radio.RadioPlayer
import com.bydmapcam.settings.Settings
import com.bydmapcam.trip.TripTracker

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val points by vm.points.collectAsState()
    val location by LocationBus.location.collectAsState()
    val activeIds by LocationBus.activeAlertIds.collectAsState()
    val infoActiveIds by LocationBus.infoActiveIds.collectAsState()
    val alertDistances by LocationBus.alertDistances.collectAsState()

    // Coordinates for a pending "save point" dialog — from the FAB (current location) or a map long-press.
    var pendingSave by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showList by remember { mutableStateOf(false) }
    var editingPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var selectedPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var focus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var headingUp by remember { mutableStateOf(Settings.headingUp(context)) }
    var bannerDismissed by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val radioState by RadioPlayer.state.collectAsState()

    val activeTrip by TripTracker.active.collectAsState()
    val restoredTrip by TripTracker.restored.collectAsState()
    val recentTrips by vm.recentTrips.collectAsState()
    val avgKmPct = remember(recentTrips) { avgKmPerPercent(recentTrips) }
    var showTripStart by remember { mutableStateOf(false) }
    var showTripFinish by remember { mutableStateOf(false) }
    var tripSummary by remember { mutableStateOf<Trip?>(null) }
    var showTripHistory by remember { mutableStateOf(false) }

    // Reset the in-app banner dismissal once you leave all alert zones, so it re-shows next time.
    LaunchedEffect(activeIds) { if (activeIds.isEmpty()) bannerDismissed = emptySet() }

    Box(Modifier.fillMaxSize()) {
        MapLibreMap(
            points = points,
            location = location,
            activeIds = activeIds,
            infoActiveIds = infoActiveIds,
            recenterTick = recenterTick,
            onMapLongClick = { lat, lng -> pendingSave = lat to lng },
            onMarkerClick = { id ->
                points.find { it.id == id }?.let {
                    selectedPoint = it
                    focus = it.lat to it.lng
                }
            },
            focus = focus,
            headingUp = headingUp,
            modifier = Modifier.fillMaxSize()
        )

        // Top overlays: stacked vertically below the status bar so they never overlap.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Live trip panel — flush at the top edge, centered, shown only while a trip runs.
            activeTrip?.let { t ->
                TripStatusCard(
                    distanceKm = t.distanceKm,
                    avgKmPerPercent = avgKmPct,
                    onFinish = { showTripFinish = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            location?.let { loc -> SpeedChip(speedMps = loc.speed) }

            val activePoints = points.filter { it.id in activeIds }
            if (activePoints.isNotEmpty() && activeIds != bannerDismissed) {
                AlertBanner(
                    points = activePoints,
                    distances = alertDistances,
                    onDismiss = { bannerDismissed = activeIds },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Settings gear, top-right.
        SmallFloatingActionButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_gear),
                contentDescription = "ตั้งค่า",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // Bottom-right controls, clear of the navigation bar.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(onClick = { recenterTick++ }) {
                LocateIcon(color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (activeTrip == null) {
                SmallFloatingActionButton(onClick = { showTripStart = true }) {
                    Text("ทริป")
                }
            }
            SmallFloatingActionButton(onClick = { showList = true }) {
                Text("จุด")
            }
            ExtendedFloatingActionButton(onClick = {
                location?.let { pendingSave = it.latitude to it.longitude }
            }) {
                Text("บันทึกจุดนี้")
            }
        }

        // Bottom-left: FM Green Wave 106.5 radio toggle (streams straight off the net).
        ExtendedFloatingActionButton(
            onClick = { RadioPlayer.toggle() },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            val tint = MaterialTheme.colorScheme.onPrimaryContainer
            if (radioState == RadioPlayer.State.BUFFERING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = tint
                )
            } else {
                RadioGlyph(playing = radioState == RadioPlayer.State.PLAYING, color = tint)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when (radioState) {
                    RadioPlayer.State.BUFFERING -> "กำลังเชื่อม…"
                    RadioPlayer.State.ERROR -> "ลองใหม่"
                    else -> "Green Wave"
                }
            )
        }

        selectedPoint?.let { p ->
            PointInfoCard(
                point = p,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp),
                onEdit = { editingPoint = p; selectedPoint = null },
                onDelete = { vm.delete(p); selectedPoint = null },
                onClose = { selectedPoint = null }
            )
        }
    }

    pendingSave?.let { (lat, lng) ->
        SavePointDialog(
            lat = lat,
            lng = lng,
            onDismiss = { pendingSave = null },
            onSave = { name, type, radius, alertEnabled, sound, infoMode ->
                vm.savePoint(name, type, lat, lng, radius, alertEnabled, sound, infoMode)
                pendingSave = null
            }
        )
    }

    if (showList) {
        PointListDialog(
            points = points,
            currentLat = location?.latitude,
            currentLng = location?.longitude,
            onDismiss = { showList = false },
            onFocus = { p ->
                showList = false
                selectedPoint = p
                focus = p.lat to p.lng
            },
            onEdit = { editingPoint = it },
            onDelete = { vm.delete(it) },
            onDeleteMany = { ids -> vm.deleteMany(ids) }
        )
    }

    editingPoint?.let { pt ->
        EditPointDialog(
            point = pt,
            onDismiss = { editingPoint = null },
            onSave = {
                vm.updatePoint(it)
                editingPoint = null
            }
        )
    }

    // A trip left running from a previous run — ask keep / finish / discard before it corrupts.
    if (restoredTrip) {
        activeTrip?.let { t ->
            RestoreTripDialog(
                startSoc = t.startSoc,
                distanceKm = t.distanceKm,
                startTime = t.startTime,
                onContinue = { TripTracker.acknowledgeRestore() },
                onFinish = { TripTracker.acknowledgeRestore(); showTripFinish = true },
                onDiscard = { vm.cancelTrip() }
            )
        }
    }

    if (showTripStart) {
        StartTripDialog(
            onDismiss = { showTripStart = false },
            onStart = { startSoc ->
                vm.startTrip(startSoc)
                showTripStart = false
            }
        )
    }

    if (showTripFinish) {
        activeTrip?.let { t ->
            FinishTripDialog(
                distanceKm = t.distanceKm,
                startSoc = t.startSoc,
                onDismiss = { showTripFinish = false },
                onDiscard = { vm.cancelTrip(); showTripFinish = false },
                onFinish = { endSoc, price ->
                    vm.finishTrip(endSoc, price) { saved ->
                        tripSummary = saved
                    }
                    showTripFinish = false
                }
            )
        } ?: run { showTripFinish = false }
    }

    tripSummary?.let { t ->
        TripSummaryDialog(
            trip = t,
            onOpenHistory = { tripSummary = null; showTripHistory = true },
            onDismiss = { tripSummary = null }
        )
    }

    if (showTripHistory) {
        TripHistoryDialog(
            trips = recentTrips,
            onDismiss = { showTripHistory = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            headingUp = headingUp,
            onHeadingUpChange = { headingUp = it; Settings.setHeadingUp(context, it) },
            onOpenTripHistory = { showSettings = false; showTripHistory = true },
            onImportCameras = {
                Toast.makeText(context, "กำลังนำเข้าฐานกล้อง…", Toast.LENGTH_SHORT).show()
                vm.importCameras { count ->
                    Toast.makeText(context, "นำเข้าเสร็จ — เพิ่ม $count จุด", Toast.LENGTH_LONG).show()
                }
                showSettings = false
            },
            onOpenOffline = { showSettings = false; showOffline = true },
            onDismiss = { showSettings = false }
        )
    }

    if (showOffline) {
        OfflineMapsDialog(onDismiss = { showOffline = false })
    }
}

@Composable
private fun SpeedChip(speedMps: Float, modifier: Modifier = Modifier) {
    val kmh = (speedMps * 3.6f).toInt().coerceAtLeast(0)
    // Tabular figures (tnum) → every digit is the same width, so the number never jitters.
    val numberStyle = TextStyle(
        color = Color.White,
        fontSize = 46.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center
    )
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Invisible "000" reserves a fixed 3-digit width so the pill never resizes
            // as the speed crosses 1 → 2 → 3 digits; the live number is centered on top.
            Text("000", style = numberStyle, modifier = Modifier.alpha(0f))
            Text("$kmh", style = numberStyle)
        }
    }
}

@Composable
private fun AlertBanner(
    points: List<AlertPoint>,
    distances: Map<Long, Int>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Highlight the nearest point's distance big in the header (live countdown).
    val nearest = points.mapNotNull { distances[it.id] }.minOrNull()
    Surface(
        modifier = modifier,
        color = Color(0xFFE53935),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
            ) {
                Text(
                    text = if (nearest != null) "⚠ ใกล้จุดเตือน — อีก $nearest ม." else "⚠ ใกล้จุดเตือน",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                points.take(3).forEach {
                    val dm = distances[it.id]
                    val tail = if (dm != null) " — $dm ม." else " (${it.type.label})"
                    Text(text = "• ${it.name}$tail", color = Color.White)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("✕", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun PointInfoCard(
    point: AlertPoint,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(point.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pointDetail(point),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "พิกัด: ${"%.5f".format(point.lat)}, ${"%.5f".format(point.lng)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("แก้ไข") }
                TextButton(onClick = onDelete) { Text("ลบ") }
                TextButton(onClick = onClose) { Text("ปิด") }
            }
        }
    }
}

/** Play triangle (stopped) / stop square (playing), drawn with Canvas — no icon dependency. */
@Composable
private fun RadioGlyph(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        if (playing) {
            val s = size.minDimension * 0.72f
            val o = (size.minDimension - s) / 2f
            drawRect(color = color, topLeft = Offset(o, o), size = Size(s, s))
        } else {
            val w = size.width
            val h = size.height
            val tri = Path().apply {
                moveTo(w * 0.22f, h * 0.15f)
                lineTo(w * 0.85f, h * 0.5f)
                lineTo(w * 0.22f, h * 0.85f)
                close()
            }
            drawPath(tri, color)
        }
    }
}

/** Simple "locate me" crosshair drawn with Canvas (no icon dependency). */
@Composable
private fun LocateIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val stroke = size.minDimension * 0.09f
        val c = center
        val r = size.minDimension / 3.4f
        drawCircle(color = color, radius = r, center = c, style = Stroke(width = stroke))
        drawCircle(color = color, radius = r * 0.30f, center = c)
        val tick = size.minDimension * 0.14f
        drawLine(color, Offset(c.x, 0f), Offset(c.x, tick), strokeWidth = stroke)
        drawLine(color, Offset(c.x, size.height), Offset(c.x, size.height - tick), strokeWidth = stroke)
        drawLine(color, Offset(0f, c.y), Offset(tick, c.y), strokeWidth = stroke)
        drawLine(color, Offset(size.width, c.y), Offset(size.width - tick, c.y), strokeWidth = stroke)
    }
}
