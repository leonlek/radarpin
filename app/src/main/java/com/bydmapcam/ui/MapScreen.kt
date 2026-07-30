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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import com.bydmapcam.sim.Simulator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bydmapcam.R
import com.bydmapcam.alert.AlertFormat
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.Trip
import com.bydmapcam.data.avgKmPerPercent
import com.bydmapcam.location.LocationBus
import com.bydmapcam.location.LocationService
import com.bydmapcam.media.MediaLink
import com.bydmapcam.radio.RadioPlayer
import com.bydmapcam.settings.Settings
import com.bydmapcam.trip.TripTracker
import kotlinx.coroutines.delay

/** Below this width the alert has to stay a full-width bar; above it, it becomes a side panel. */
private const val RAIL_MIN_WIDTH_DP = 600

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val realPoints by vm.points.collectAsState()
    val location by LocationBus.location.collectAsState()
    val realActiveIds by LocationBus.activeAlertIds.collectAsState()
    val realInfoIds by LocationBus.infoActiveIds.collectAsState()
    val realDistances by LocationBus.alertDistances.collectAsState()

    // The simulator lays fake state over the live one (emulator only) so every alert style can be
    // seen without driving. Nothing below needs to know which of the two it's rendering.
    val sim by Simulator.state.collectAsState()
    var showSim by remember { mutableStateOf(false) }
    val points = remember(realPoints, sim) { sim?.let { realPoints + it.points } ?: realPoints }
    val activeIds = sim?.activeIds ?: realActiveIds
    val infoActiveIds = sim?.infoIds ?: realInfoIds
    val alertDistances = sim?.distances ?: realDistances

    // A wide screen (head unit, or a phone turned sideways) has room for the alert as a side panel;
    // a portrait phone doesn't, so it keeps the full-width bar.
    val railBanner = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP

    // Coordinates for a pending "save point" dialog — from the FAB (current location) or a map long-press.
    var pendingSave by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showList by remember { mutableStateOf(false) }
    var editingPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var zoomInTick by remember { mutableIntStateOf(0) }
    var zoomOutTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var selectedPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var focus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var headingUp by remember { mutableStateOf(Settings.headingUp(context)) }
    var bannerDismissed by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val radioState by RadioPlayer.state.collectAsState()
    val nowPlaying by MediaLink.nowPlaying.collectAsState()

    // Watch other apps' media sessions while we're on screen. With notification access we get live
    // callbacks and the track name; without it we poll for "is media actually playing" so the bar
    // still appears the moment music starts — just without a title.
    LaunchedEffect(Unit) {
        MediaLink.start(context)
        while (!MediaLink.hasAccess(context)) {
            MediaLink.refreshWithoutAccess(context)
            delay(2000)
            MediaLink.start(context) // picks up the moment access is granted
        }
    }

    val activeTrip by TripTracker.active.collectAsState()
    val restoredTrip by TripTracker.restored.collectAsState()
    val recentTrips by vm.recentTrips.collectAsState()
    val avgKmPct = remember(recentTrips) { avgKmPerPercent(recentTrips) }
    var showTripStart by remember { mutableStateOf(false) }
    var showTripSoc by remember { mutableStateOf(false) }
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
            distances = alertDistances,
            recenterTick = recenterTick,
            zoomInTick = zoomInTick,
            zoomOutTick = zoomOutTick,
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

        // Top overlays. The speed sits in the very top-left corner and drops below the trip card
        // while a trip runs; the settings gear owns its own column on the right so the trip card /
        // banner can never slide under it.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // On a narrow phone the trip card can't share the top row with the speed and
                    // the gear, so it stacks under the speed instead.
                    if (!railBanner) {
                        activeTrip?.let { t ->
                            TripStatusCard(
                                trip = t,
                                avgKmPerPercent = avgKmPct,
                                onSetSoc = { showTripSoc = true },
                                onFinish = { showTripFinish = true }
                            )
                        }
                    }

                    val simSpeed = sim?.speedKmh
                    if (simSpeed != null) SpeedChip(speedMps = simSpeed / 3.6f)
                    else location?.let { loc -> SpeedChip(speedMps = loc.speed) }
                }

                if (railBanner) {
                    activeTrip?.let { t ->
                        TripStatusCard(
                            trip = t,
                            avgKmPerPercent = avgKmPct,
                            onSetSoc = { showTripSoc = true },
                            onFinish = { showTripFinish = true },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                SmallFloatingActionButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gear),
                        contentDescription = "ตั้งค่า",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            val activePoints = points.filter { it.id in activeIds }
            if (activePoints.isNotEmpty() && activeIds != bannerDismissed) {
                AlertBanner(
                    points = activePoints,
                    distances = alertDistances,
                    rail = railBanner,
                    onSelect = { p ->
                        selectedPoint = p
                        focus = p.lat to p.lng
                    },
                    onDismiss = { bannerDismissed = activeIds },
                    modifier = if (railBanner) {
                        // Clear of the right-hand button column, which reaches up this far on a
                        // tall head-unit screen.
                        Modifier
                            .align(Alignment.End)
                            .padding(end = 64.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    }
                )
            }
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
            if (Simulator.isEmulator) {
                SmallFloatingActionButton(
                    onClick = { showSim = true },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text("จำลอง", fontSize = 11.sp)
                }
            }
            SmallFloatingActionButton(onClick = { zoomInTick++ }) {
                Text("+", fontSize = 22.sp)
            }
            SmallFloatingActionButton(onClick = { zoomOutTick++ }) {
                Text("−", fontSize = 22.sp)
            }
            SmallFloatingActionButton(onClick = { recenterTick++ }) {
                LocateIcon(color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            // Always there, so the button never moves under you: it starts a trip when none is
            // running, and asks for the current % when one is.
            SmallFloatingActionButton(
                onClick = { if (activeTrip == null) showTripStart = true else showTripSoc = true }
            ) {
                Text("ทริป")
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

        // Bottom-left: what's playing elsewhere (only while something is), above our own radio.
        // Extra bottom padding leaves the OpenStreetMap credit strip uncovered — it has to stay
        // readable, so our buttons sit above it rather than on top of it.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            (sim?.nowPlaying ?: nowPlaying)?.let { np ->
                MediaBar(
                    nowPlaying = np,
                    onPrevious = { MediaLink.previous(context) },
                    onPlayPause = {
                        // Starting someone else's music: our radio gets out of the way.
                        if (!np.playing) RadioPlayer.stop()
                        MediaLink.playPause(context)
                    },
                    onNext = { MediaLink.next(context) }
                )
            }

            // FM Green Wave 106.5 radio toggle (streams straight off the net).
            ExtendedFloatingActionButton(onClick = {
                // …and the same courtesy in reverse.
                if (radioState == RadioPlayer.State.STOPPED || radioState == RadioPlayer.State.ERROR) {
                    MediaLink.pause(context)
                }
                RadioPlayer.toggle()
            }) {
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

    // Mid-trip battery reading → live km/1% right now.
    if (showTripSoc) {
        activeTrip?.let { t ->
            TripSocDialog(
                startSoc = t.startSoc,
                distanceKm = t.distanceKm,
                onDismiss = { showTripSoc = false },
                onSet = { pct ->
                    vm.setTripSoc(pct)
                    showTripSoc = false
                }
            )
        } ?: run { showTripSoc = false }
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

    if (showSim) {
        SimulateDialog(
            active = sim?.label,
            onPick = { scenario ->
                when (scenario) {
                    SimScenario.ALERT_FAR -> Simulator.alertFar(location)
                    SimScenario.ALERT_NEAR -> Simulator.alertNear(location)
                    SimScenario.ALERT_EV -> Simulator.alertEv(location)
                    SimScenario.ALERT_POI -> Simulator.alertPoi(location)
                    SimScenario.ALERT_TWO -> Simulator.alertTwo(location)
                    SimScenario.INFO_POP -> Simulator.infoPop(location)
                    SimScenario.ALL -> {
                        Simulator.everything(location)
                        if (TripTracker.active.value == null) vm.startTrip(85)
                    }
                    SimScenario.OVERLAY -> LocationService.simulateOverlay(context)
                    SimScenario.MEDIA -> Simulator.media()
                    SimScenario.TRIP -> vm.startTrip(85)
                    SimScenario.OFF -> {
                        Simulator.clear()
                        LocationService.clearSimulatedOverlay(context)
                        vm.cancelTrip()
                    }
                }
                bannerDismissed = emptySet()
                showSim = false
            },
            onDismiss = { showSim = false }
        )
    }
}

enum class SimScenario { ALERT_FAR, ALERT_EV, ALERT_POI, ALERT_NEAR, ALERT_TWO, INFO_POP, OVERLAY, MEDIA, TRIP, ALL, OFF }

/** Emulator-only shortcut list for putting the UI into each state worth looking at. */
@Composable
private fun SimulateDialog(
    active: String?,
    onPick: (SimScenario) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("จำลองสถานการณ์") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = active?.let { "กำลังจำลอง: $it" } ?: "ใช้ดูหน้าตาแต่ละแบบโดยไม่ต้องออกไปขับ",
                    style = MaterialTheme.typography.bodySmall
                )
                SimHeader("เตือนตามประเภทจุด")
                SimRow("🔴 กล้องจับความเร็ว — 350 ม.") { onPick(SimScenario.ALERT_FAR) }
                SimRow("🟢 ปั๊ม EV — เด้งไอคอน 150 ม.") { onPick(SimScenario.ALERT_EV) }
                SimRow("🔵 จุดสนใจ — เด้งไอคอน 130 ม.") { onPick(SimScenario.ALERT_POI) }

                SimHeader("รูปแบบอื่นของการเตือน")
                SimRow("⚠ ถึงจุดแล้ว (หยุดนับ + สีเข้ม)") { onPick(SimScenario.ALERT_NEAR) }
                SimRow("⚠ กล้อง 2 ตัวพร้อมกัน (มีบรรทัดถัดไป)") { onPick(SimScenario.ALERT_TWO) }
                SimRow("ℹ︎ จุดแบบ info (ไอคอนเด้ง ไม่มีแบนเนอร์)") { onPick(SimScenario.INFO_POP) }
                SimRow("📱 การ์ดนอกแอป (กด Home ต่อ)") { onPick(SimScenario.OVERLAY) }

                SimHeader("ส่วนอื่นของแอป")
                SimRow("🎵 แถบเพลง") { onPick(SimScenario.MEDIA) }
                SimRow("🚗 การ์ดทริป (แบต 85%)") { onPick(SimScenario.TRIP) }
                SimRow("🧩 ทุกอย่างพร้อมกัน") { onPick(SimScenario.ALL) }

                SimHeader("")
                SimRow("■ หยุดจำลองทั้งหมด") { onPick(SimScenario.OFF) }
            }
        }
    )
}

@Composable
private fun SimHeader(text: String) {
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
    } else {
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun SimRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpeedChip(speedMps: Float, modifier: Modifier = Modifier) {
    val kmh = (speedMps * 3.6f).toInt().coerceAtLeast(0)
    // Tabular figures (tnum) → every digit is the same width, so the number never jitters.
    val numberStyle = TextStyle(
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontSize = 46.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center
    )
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
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
    rail: Boolean,
    onSelect: (AlertPoint) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (rail) {
        AlertRail(points, distances, onSelect, onDismiss, modifier)
        return
    }
    // Highlight the nearest point's distance big in the header (live countdown, floored at 100 m).
    val nearest = points.mapNotNull { distances[it.id] }.minOrNull()
    val header = when {
        nearest == null -> "⚠ ใกล้จุดเตือน"
        nearest >= AlertFormat.FLOOR_M -> "⚠ ใกล้จุดเตือน — อีก $nearest ม."
        else -> "⚠ ถึงจุดเตือนแล้ว"
    }
    // Tapping the banner jumps the map to the point being warned about (the nearest one),
    // same as tapping its marker — you're already in the app, so "open the app" would be a no-op.
    val nearestPoint = points.minByOrNull { distances[it.id] ?: Int.MAX_VALUE } ?: points.first()
    Surface(
        onClick = { onSelect(nearestPoint) },
        modifier = modifier,
        color = Color(nearestPoint.type.alertColor),
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
                    text = header,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                points.take(3).forEach {
                    val dm = distances[it.id]
                    val tail = if (dm != null) " — ${AlertFormat.countdown(dm)}" else " (${it.type.label})"
                    Text(text = "• ${it.name}$tail", color = Color.White)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("✕", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

/**
 * Side panel for wide screens: the distance is the headline because that's the one number a driver
 * needs off a glance, and it lives on the right edge where it covers neither the speed nor the road
 * ahead in the middle of the map.
 */
@Composable
private fun AlertRail(
    points: List<AlertPoint>,
    distances: Map<Long, Int>,
    onSelect: (AlertPoint) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sorted = points.sortedBy { distances[it.id] ?: Int.MAX_VALUE }
    val lead = sorted.first()
    val leadDistance = distances[lead.id]
    Surface(
        onClick = { onSelect(lead) },
        modifier = modifier.width(232.dp),
        color = Color(
            if (leadDistance != null && leadDistance < AlertFormat.FLOOR_M) lead.type.alertColorNear
            else lead.type.alertColor
        ),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, top = 10.dp, end = 10.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = lead.type.label,
                    color = Color.White.copy(alpha = .88f),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f).padding(top = 6.dp)
                )
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("✕", color = Color.White, fontSize = 18.sp)
                }
            }

            if (leadDistance != null && leadDistance >= AlertFormat.FLOOR_M) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$leadDistance",
                        color = Color.White,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        lineHeight = 54.sp
                    )
                    Text(
                        text = " ม.",
                        color = Color.White.copy(alpha = .9f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 7.dp)
                    )
                }
            } else {
                Text(
                    text = "ถึงจุดแล้ว",
                    color = Color.White,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 44.sp
                )
            }

            Text(
                text = lead.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Only ever one line about what's behind it — a list would be unreadable at speed.
            sorted.getOrNull(1)?.let { next ->
                HorizontalDivider(
                    color = Color.White.copy(alpha = .3f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row {
                    Text(
                        text = "ถัดไป · ${next.name}",
                        color = Color.White.copy(alpha = .9f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    distances[next.id]?.let {
                        Text(
                            text = "  $it ม.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
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
