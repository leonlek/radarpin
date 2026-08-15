package com.bydmapcam.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bydmapcam.R
import com.bydmapcam.alert.AlertFormat
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.GeoPoint
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.Side
import com.bydmapcam.data.SideRule
import com.bydmapcam.data.Trip
import com.bydmapcam.data.avgKmPerPercent
import com.bydmapcam.parking.ParkingRules
import com.bydmapcam.parking.ParkingState
import com.bydmapcam.location.LocationBus
import com.bydmapcam.location.LocationService
import com.bydmapcam.media.MediaLink
import com.bydmapcam.radio.RadioPlayer
import com.bydmapcam.settings.Settings
import com.bydmapcam.trip.TripTracker
import com.bydmapcam.update.Updates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Below this width the alert has to stay a full-width bar; above it, it becomes a side panel. */
private const val RAIL_MIN_WIDTH_DP = 600

/** Where a point is about to be saved, and — when it was captured from a moving car — the
 *  direction the road runs there. */
private data class PendingSave(val lat: Double, val lng: Double, val headingDeg: Double?)

/** The heading to record with a point, or null when the car is too slow for it to mean anything:
 *  a standing GPS invents a direction, and a wrong road axis is worse than none. */
private fun drivingHeading(loc: android.location.Location): Double? =
    if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= LocationService.MOVING_SPEED_MPS) {
        loc.bearing.toDouble()
    } else {
        null
    }

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

    // A pending "save point" dialog — from the FAB (where the car is) or a map long-press. Saving
    // while driving also captures which way the road runs, which is free here and impossible later.
    var pendingSave by remember { mutableStateOf<PendingSave?>(null) }
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
    var meIcon by remember { mutableStateOf(Settings.meIcon(context)) }

    // Parking blocks: the kerb lines on the map, the block being drawn, and the one tapped open.
    val parkingBlocks by vm.parkingBlocks.collectAsState()
    var parkingLines by remember { mutableStateOf(Settings.parkingLines(context)) }
    var draft by remember { mutableStateOf<ParkingDraft?>(null) }
    var pendingBlock by remember { mutableStateOf<PendingBlock?>(null) }
    var selectedBlock by remember { mutableStateOf<ParkingBlock?>(null) }
    var showParkingList by remember { mutableStateOf(false) }
    // The instant the kerb colours describe. It only has to move when the answer can change:
    // at midnight always, and every minute for blocks that carry an hours ban.
    var parkingAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val parkedOn by LocationBus.parkedOn.collectAsState()
    // Waved away by value, so the next park — a different block, or the same one on another day —
    // brings the card back without needing a reset anywhere.
    var parkedNoticeDismissed by remember { mutableStateOf<LocationBus.ParkedOn?>(null) }
    val timedBlocks = remember(parkingBlocks) { parkingBlocks.any { it.banFromMin != null } }
    LaunchedEffect(timedBlocks) {
        while (true) {
            delay(20_000)
            val now = System.currentTimeMillis()
            if (timedBlocks || ParkingRules.dayKey(now) != ParkingRules.dayKey(parkingAt)) {
                parkingAt = now
            }
        }
    }
    // Shared with the service's over-other-apps card, so a ✕ in either place settles both.
    val dismissedIds by LocationBus.dismissedIds.collectAsState()
    val radioState by RadioPlayer.state.collectAsState()
    val nowPlaying by MediaLink.nowPlaying.collectAsState()

    // Watch other apps' media sessions while we're on screen. Reading them needs notification
    // access, so keep looking until it's granted — until then there is no session to drive and
    // therefore no bar (see MediaLink: a bar we can't fully control is worse than none).
    LaunchedEffect(Unit) {
        MediaLink.start(context)
        while (!MediaLink.hasAccess(context)) {
            delay(2000)
            MediaLink.start(context) // picks up the moment access is granted
        }
    }

    // A sideloaded app has to tell you about its own new versions. Once when the app opens, never
    // while driving: the check is a single small file and then it is quiet for a day.
    val scope = rememberCoroutineScope()
    val updateState by Updates.state.collectAsState()
    LaunchedEffect(Unit) { Updates.check(context) }

    val activeTrip by TripTracker.active.collectAsState()
    val restoredTrip by TripTracker.restored.collectAsState()
    val recentTrips by vm.recentTrips.collectAsState()
    val avgKmPct = remember(recentTrips) { avgKmPerPercent(recentTrips) }
    var showTripStart by remember { mutableStateOf(false) }
    var showTripSoc by remember { mutableStateOf(false) }
    var showTripFinish by remember { mutableStateOf(false) }
    var tripSummary by remember { mutableStateOf<Trip?>(null) }
    var showTripHistory by remember { mutableStateOf(false) }

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
            // A spot picked off the map is not where the car is, so there's no road direction to
            // record — that point warns from every direction, as points always did.
            onMapLongClick = { lat, lng ->
                if (draft == null) pendingSave = PendingSave(lat, lng, null)
            },
            onMarkerClick = { id ->
                points.find { it.id == id }?.let {
                    selectedPoint = it
                    selectedBlock = null
                    focus = it.lat to it.lng
                }
            },
            focus = focus,
            headingUp = headingUp,
            meIcon = meIcon,
            // The block being re-traced drops out of the painted set: its old kerbs under the new
            // draft would be two answers to the same question.
            parkingBlocks = if (parkingLines) {
                parkingBlocks.filter { it.id != draft?.editing?.id }
            } else {
                emptyList()
            },
            parkingAt = parkingAt,
            draft = draft,
            onMapTap = { tap, trace ->
                val d = draft
                if (d == null) {
                    false
                } else {
                    when (d.stage) {
                        // Tracing: the tap brings the road it landed on with it, so a bend arrives
                        // already curved. Nothing snapped means the tap itself is the whole answer.
                        ParkingDraft.Stage.PATH ->
                            draft = d.plus(trace.ifEmpty { listOf(tap) })
                        // Picking the kerb: which side of the traced line the tap fell on IS the
                        // answer, so it works whether they hit the drawn kerb or the road beside it.
                        ParkingDraft.Stage.SIDE ->
                            ParkingRules.nearestOnPath(d.path, tap.lat, tap.lng)?.side?.let { side ->
                                pendingBlock = PendingBlock(d.path, side, d.editing)
                            }
                    }
                    true
                }
            },
            onParkingClick = { id ->
                parkingBlocks.find { it.id == id }?.let {
                    selectedBlock = it
                    selectedPoint = null
                }
            },
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

            UpdateCard(
                state = updateState,
                onUpdate = { available -> scope.launch { Updates.download(context, available) } },
                onDismiss = { Updates.dismiss() },
                onOpenPage = { Updates.openDownloadPage(context) },
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            // Parked on a mapped block, with something to act on. Shown here rather than over the
            // map's bottom half because the driver is stopped and reading, not glancing.
            parkedOn?.takeIf { it != parkedNoticeDismissed }
                ?.takeIf { it.state != ParkingState.ALLOWED || it.flipsOvernight }
                ?.let { p ->
                    ParkedOnCard(
                        parked = p,
                        onDismiss = { parkedNoticeDismissed = p },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

            // Drawing owns the top strip: it's a mode, and the way out of it has to be in sight.
            draft?.let { d ->
                ParkingDrawBar(
                    draft = d,
                    onUndo = {
                        draft = when (d.stage) {
                            ParkingDraft.Stage.PATH -> d.undo()
                            ParkingDraft.Stage.SIDE -> d.copy(stage = ParkingDraft.Stage.PATH)
                        }
                    },
                    onCancel = { draft = null; pendingBlock = null },
                    onNext = { draft = d.copy(stage = ParkingDraft.Stage.SIDE) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            val activePoints = points.filter { it.id in activeIds }
            if (activePoints.isNotEmpty() && activeIds != dismissedIds) {
                AlertBanner(
                    points = activePoints,
                    distances = alertDistances,
                    rail = railBanner,
                    onSelect = { p ->
                        selectedPoint = p
                        focus = p.lat to p.lng
                    },
                    onDismiss = { LocationBus.dismissAlerts(activeIds) },
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
            // While a block is being traced the map is a drawing surface: every other button here
            // would either add something else to it or take you off it mid-draw.
            if (draft == null) {
                // Always there, so the button never moves under you: it starts a trip when none is
                // running, and asks for the current % when one is.
                SmallFloatingActionButton(
                    onClick = { if (activeTrip == null) showTripStart = true else showTripSoc = true }
                ) {
                    Text("ทริป")
                }
                // Mirrors "จุด": the button opens the list of what you've mapped, and drawing a new
                // one starts from in there — same shape of thing, same way in.
                SmallFloatingActionButton(onClick = { showParkingList = true }) {
                    Text("ที่จอด", fontSize = 11.sp)
                }
                SmallFloatingActionButton(onClick = { showList = true }) {
                    Text("จุด")
                }
                ExtendedFloatingActionButton(onClick = {
                    location?.let { loc ->
                        pendingSave = PendingSave(loc.latitude, loc.longitude, drivingHeading(loc))
                    }
                }) {
                    Text("บันทึกจุดนี้")
                }
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

        selectedBlock?.let { b ->
            ParkingInfoCard(
                block = b,
                at = parkingAt,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp),
                onEdit = {
                    pendingBlock = PendingBlock(b.path, b.pickedSide(), b)
                    selectedBlock = null
                },
                onDelete = { vm.deleteParkingBlock(b); selectedBlock = null },
                onClose = { selectedBlock = null }
            )
        }
    }

    if (showParkingList) {
        ParkingListDialog(
            blocks = parkingBlocks,
            currentLat = location?.latitude,
            currentLng = location?.longitude,
            at = parkingAt,
            onDismiss = { showParkingList = false },
            onDraw = {
                showParkingList = false
                selectedPoint = null
                selectedBlock = null
                draft = ParkingDraft()
            },
            onFocus = { b ->
                showParkingList = false
                selectedPoint = null
                selectedBlock = b
                focus = b.center()
            },
            onEdit = { b ->
                showParkingList = false
                pendingBlock = PendingBlock(b.path, b.pickedSide(), b)
            },
            onDelete = { vm.deleteParkingBlock(it) }
        )
    }

    pendingBlock?.let { pending ->
        val existing = pending.existing
        ParkingBlockDialog(
            title = if (existing == null) "บล็อกจอดรถ" else "แก้ไขบล็อกจอดรถ",
            oddSide = pending.oddSide,
            initialName = existing?.name ?: "บล็อก ${parkingBlocks.size + 1}",
            initialLeft = existing?.leftRule
                ?: if (pending.oddSide == Side.LEFT) SideRule.ODD_DAYS else SideRule.EVEN_DAYS,
            initialRight = existing?.rightRule
                ?: if (pending.oddSide == Side.LEFT) SideRule.EVEN_DAYS else SideRule.ODD_DAYS,
            initialBanFrom = existing?.banFromMin,
            initialBanTo = existing?.banToMin,
            // Offered only for a block that already exists: re-tracing a line you are in the middle
            // of drawing is just "ย้อนกลับ".
            onRedraw = existing?.let {
                {
                    pendingBlock = null
                    selectedBlock = null
                    // Each original vertex counts as its own tap, so undo walks the old line back
                    // one point at a time — which is what "ถอย" meant before any of this.
                    draft = ParkingDraft(
                        path = it.path,
                        taps = it.path.indices.map { i -> i + 1 },
                        editing = it
                    )
                }
            },
            // Cancelling drops back to the map with the draft still there, so a mis-tapped kerb is
            // one tap to correct rather than a re-trace.
            onDismiss = { pendingBlock = null },
            onSave = { form ->
                vm.saveParkingBlock(pending, form)
                pendingBlock = null
                draft = null
            }
        )
    }

    pendingSave?.let { pending ->
        SavePointDialog(
            lat = pending.lat,
            lng = pending.lng,
            headingDeg = pending.headingDeg,
            onDismiss = { pendingSave = null },
            onSave = { form ->
                vm.savePoint(form, pending.lat, pending.lng, pending.headingDeg)
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
            onOpenHistory = { showTripStart = false; showTripHistory = true },
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
            meIcon = meIcon,
            onMeIconChange = { meIcon = it; Settings.setMeIcon(context, it) },
            parkingLines = parkingLines,
            onParkingLinesChange = { parkingLines = it; Settings.setParkingLines(context, it) },
            onOpenTripHistory = { showSettings = false; showTripHistory = true },
            onCheckUpdate = {
                showSettings = false
                Toast.makeText(context, "กำลังตรวจอัปเดต…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val message = when (Updates.check(context, force = true)) {
                        Updates.Result.FOUND -> null // the card says it better than a toast can
                        Updates.Result.UP_TO_DATE -> "ใช้เวอร์ชันล่าสุดอยู่แล้ว"
                        else -> "ตรวจอัปเดตไม่สำเร็จ — เช็คเน็ตแล้วลองใหม่"
                    }
                    message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            },
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
                    // The real thing needs two minutes of standing still, which is two minutes
                    // nobody will spend at a desk; the card reads the same bus either way.
                    SimScenario.PARK_WRONG -> LocationBus.updateParkedOn(
                        LocationBus.ParkedOn(
                            blockId = -1L,
                            blockName = "ซอยอารีย์ 5 ต้นซอย",
                            side = Side.LEFT,
                            state = ParkingState.WRONG_DAY,
                            flipsOvernight = false
                        )
                    )
                    SimScenario.PARK_FLIP -> LocationBus.updateParkedOn(
                        LocationBus.ParkedOn(
                            blockId = -1L,
                            blockName = "ซอยอารีย์ 5 ต้นซอย",
                            side = Side.RIGHT,
                            state = ParkingState.ALLOWED,
                            flipsOvernight = true
                        )
                    )
                    SimScenario.MEDIA -> Simulator.media()
                    SimScenario.TRIP -> vm.startTrip(85)
                    SimScenario.OFF -> {
                        Simulator.clear()
                        LocationService.clearSimulatedOverlay(context)
                        LocationBus.updateParkedOn(null)
                        vm.cancelTrip()
                    }
                }
                LocationBus.dismissAlerts(emptySet())
                showSim = false
            },
            onDismiss = { showSim = false }
        )
    }
}

enum class SimScenario {
    ALERT_FAR, ALERT_EV, ALERT_POI, ALERT_NEAR, ALERT_TWO, INFO_POP, OVERLAY,
    PARK_WRONG, PARK_FLIP, MEDIA, TRIP, ALL, OFF
}

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

                SimHeader("ที่จอดรถ")
                SimRow("🅿️ จอดผิดฝั่ง") { onPick(SimScenario.PARK_WRONG) }
                SimRow("🅿️ จอดถูกฝั่ง แต่เที่ยงคืนสลับ") { onPick(SimScenario.PARK_FLIP) }

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
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 88.dp)
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
            DismissCorner(cornerRadius = 12.dp, onDismiss = onDismiss)
        }
    }
}

/**
 * The ✕ owns the whole top-right corner of a banner, edge to edge. A glyph-sized button is
 * unhittable from the driver's seat of a moving car; the glyph only marks where the target is.
 */
@Composable
internal fun BoxScope.DismissCorner(cornerRadius: Dp, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(width = 84.dp, height = 64.dp)
            .clip(RoundedCornerShape(topEnd = cornerRadius))
            .clickable(onClick = onDismiss)
            .padding(top = 10.dp, end = 16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Text("✕", color = Color.White, fontSize = 22.sp)
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
        modifier = modifier.width(270.dp),
        color = Color(
            if (leadDistance != null && leadDistance < AlertFormat.FLOOR_M) lead.type.alertColorNear
            else lead.type.alertColor
        ),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp
    ) {
        Box {
            Column(Modifier.padding(start = 18.dp, top = 12.dp, end = 12.dp, bottom = 16.dp)) {
                Text(
                    text = lead.type.label,
                    color = Color.White.copy(alpha = .88f),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 6.dp, end = 76.dp)
                )

                if (leadDistance != null && leadDistance >= AlertFormat.FLOOR_M) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$leadDistance",
                            color = Color.White,
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            lineHeight = 64.sp
                        )
                        Text(
                            text = " ม.",
                            color = Color.White.copy(alpha = .9f),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                } else {
                    Text(
                        text = "ถึงจุดแล้ว",
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 50.sp
                    )
                }

                Text(
                    text = lead.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
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
            DismissCorner(cornerRadius = 16.dp, onDismiss = onDismiss)
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
