package com.bydmapcam.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.location.Location
import android.view.Display
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.bydmapcam.MainActivity
import com.bydmapcam.R
import com.bydmapcam.alert.AlertFormat
import com.bydmapcam.alert.Beeper
import com.bydmapcam.alert.Speaker
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.ParkingRepository
import com.bydmapcam.data.PointRepository
import com.bydmapcam.data.TrackCell
import com.bydmapcam.data.TrackRepository
import com.bydmapcam.data.PointType
import com.bydmapcam.parking.ParkingAlarm
import com.bydmapcam.parking.ParkingNotifier
import com.bydmapcam.parking.ParkingRules
import com.bydmapcam.parking.ParkingState
import com.bydmapcam.settings.Settings
import com.bydmapcam.trip.TripTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Foreground service that tracks GPS and beeps when the car enters a saved point's radius. */
class LocationService : LifecycleService(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var displayManager: DisplayManager
    private lateinit var repository: PointRepository
    private lateinit var parkingRepository: ParkingRepository
    private lateinit var trackRepository: TrackRepository

    @Volatile
    private var points: List<AlertPoint> = emptyList()

    @Volatile
    private var parkingBlocks: List<ParkingBlock> = emptyList()
    private var insideIds: Set<Long> = emptySet()
    private var lastLocation: Location? = null
    private var overlayView: View? = null
    private var overlayType: TextView? = null
    private var overlayDistance: TextView? = null
    private var overlayName: TextView? = null
    private var overlayNext: TextView? = null

    /** Fake card pushed in from the simulator so the over-other-apps look can be checked at a desk. */
    @Volatile
    private var simOverlay: OverlayContent? = null

    /** When the car first stopped in the current stretch (0 = it's moving). */
    private var stationarySince = 0L

    /** Parked inside an alert zone → alerts muted until the car drives off again. */
    private var parked = false

    /**
     * Whether the current parked spell has been judged against the parking blocks. Not the same
     * question as [parked]: the car can already be standing still when we first learn where it is
     * — the app opened after the driver got out, the head unit rebooted overnight, the blocks
     * arrived from the database a moment later — and every one of those is a park we never saw
     * begin, so hanging the check on the transition alone loses it.
     */
    private var parkedChecked = false

    /** When each point last raised a warning, so a U-turn doesn't raise the same one twice.
     *  In memory only: the service outlives any single drive, and a restart is a fresh road. */
    private val lastAlertAt = mutableMapOf<Long, Long>()

    /** The square painted last, so standing still doesn't hammer the database with the same one. */
    private var lastCellY = Int.MIN_VALUE
    private var lastCellX = Int.MIN_VALUE

    /** When the screen last went dark, 0 = it's on (or we never saw it go off). */
    private var screenOffAt = 0L

    /** When a wake last opened the app, so the second signal of the same wake-up is ignored. */
    private var lastWakeOpenAt = 0L

    /**
     * The other way a car tells us it has been switched on. A head unit that suspends instead of
     * powering down never broadcasts a boot — the driver turns the key and the same Android that
     * was running before simply lights the screen again — so BOOT_COMPLETED alone can never open
     * the app there. A screen that has been dark for a while is that moment.
     */
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenWake()
            }
        }
    }

    /**
     * The same question asked of the display itself. A head unit can cut the panel while Android
     * never sleeps — no SCREEN_OFF, no SCREEN_ON, nothing to wait for — and in that case the
     * display's own state is the only place the change shows up.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val on = displayManager.getDisplay(displayId)?.state == Display.STATE_ON
            if (on) onScreenWake() else onScreenOff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = PointRepository(this)
        parkingRepository = ParkingRepository(this)
        trackRepository = TrackRepository(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Start out parked if that's how we were left: otherwise re-opening the app while parked
        // inside a camera's radius beeps + banners all over again every single time.
        parked = getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).getBoolean(KEY_PARKED, false)
        // Where the car was left is the one piece of state that matters most after a restart —
        // that restart is often the app being opened again on the walk back to it.
        LocationBus.updateParkedSpot(ParkedSpot.read(this))

        repository.observeAll()
            .onEach { points = it; recompute() }
            .launchIn(lifecycleScope)

        // Kept in memory for the same reason the points are: the check runs the instant the car
        // settles, which is no time to be waiting on a query.
        parkingRepository.observeAll()
            .onEach {
                parkingBlocks = it
                // Drawing the block you are standing on should answer the question right then,
                // not on the next drive, so a change to the blocks re-opens the verdict.
                parkedChecked = false
                recompute()
            }
            .launchIn(lifecycleScope)

        // Show/hide the over-other-apps overlay as our own UI comes and goes.
        AppState.inForeground
            .onEach { updateOverlay() }
            .launchIn(lifecycleScope)

        // Registered at runtime, not in the manifest: since Android 8 a manifest receiver is not
        // given SCREEN_ON at all, and this only means anything while we're running anyway.
        registerReceiver(
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
        displayManager = getSystemService(DisplayManager::class.java)
        runCatching { displayManager.registerDisplayListener(displayListener, null) }

        // A heartbeat the settings screen reads back. Whether this survives the car being off is
        // the whole question behind "auto-start does nothing" on a head unit.
        lifecycleScope.launch {
            while (true) {
                Settings.recordAlive(this@LocationService)
                delay(ALIVE_TICK_MS)
            }
        }
    }

    /**
     * Screen woke after a long sleep — on a head unit that is the car being switched on. Brief
     * blanks (the panel dimming out at a red light, the driver tapping it off for a moment) are
     * deliberately ignored: yanking someone out of the app they were using is worse than not
     * opening at all.
     */
    private fun onScreenOff() {
        screenOffAt = System.currentTimeMillis()
        // Recorded so the settings screen can say whether this unit reports a dark screen at all.
        Settings.recordScreenOff(this)
    }

    private fun onScreenWake() {
        val sleptFor = if (screenOffAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - screenOffAt
        android.util.Log.i(
            "RadarPinWake",
            "wake auto=${Settings.autoStartOnBoot(this)} fg=${AppState.inForeground.value} sleptMs=$sleptFor"
        )
        if (sleptFor < WAKE_MIN_SLEEP_MS) return
        // Only forget how long it was dark once that fact has been spent on an actual open —
        // otherwise a call swallowed by the debounce would throw away the evidence.
        if (autoOpen(Settings.WAKE_ACTION)) screenOffAt = 0L
    }

    /**
     * Open the app because [reason] says the car is being used again. Guarded by the driver's
     * setting, by the app not already being on screen, and by a debounce — SCREEN_ON and
     * USER_PRESENT arrive together on one wake-up, and only one of them should start anything.
     */
    private fun autoOpen(reason: String): Boolean {
        if (!Settings.autoStartOnBoot(this)) return false
        // Running in the background is the whole point of these triggers when the driver has asked
        // for no window: we are already here, alerting. Traced anyway — "did the car tell us it
        // woke up" is still the question the settings screen has to answer.
        if (!Settings.autoStartShowApp(this)) {
            Settings.recordBootTrace(this, reason, Settings.BOOT_SERVICE_ONLY)
            return false
        }
        if (AppState.inForeground.value) return false
        val now = System.currentTimeMillis()
        if (now - lastWakeOpenAt < WAKE_DEBOUNCE_MS) return false
        lastWakeOpenAt = now
        openApp()
        Settings.recordBootTrace(
            this,
            reason,
            if (Settings.canDrawOverlays(this)) Settings.BOOT_STARTED else Settings.BOOT_NO_OVERLAY
        )
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!startAsForeground()) {
            // Not allowed to run headless at this moment (see startAsForeground). Stop cleanly —
            // a service that never reaches the foreground is killed by the system anyway, and
            // MainActivity starts us again the instant it is on screen.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SIM_OVERLAY -> {
                simOverlay = OverlayContent(
                    type = PointType.SPEED_CAMERA.label,
                    distance = "180 ม.",
                    name = "กล้องหน้าโรงเรียน",
                    next = "ถัดไป · ปั๊ม EV บางจาก   430 ม.",
                    color = PointType.SPEED_CAMERA.alertColor.toInt()
                )
                updateOverlay()
            }
            ACTION_SIM_CLEAR -> {
                simOverlay = null
                updateOverlay()
            }
        }
        requestUpdates()
        return START_STICKY
    }

    /**
     * @return false when the system refused to let us go foreground.
     *
     * Android 14+ rejects a *location*-type foreground service started while the app is in the
     * background — a boot broadcast being the case that matters here — and it rejects it by throwing
     * SecurityException out of [startForeground]. Uncaught, that killed the whole process at boot,
     * taking down the window the boot receiver had just asked for: "open the app when the car
     * starts" turned into "nothing happens at all". Refusal is a normal outcome, not a crash.
     */
    private fun startAsForeground(): Boolean {
        val notification = buildNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.isSuccess
    }

    private fun requestUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        try {
            // minDistance = 0: fire every ~1s even when stationary, so alerts stay live in a parked/idling car.
            locationManager.requestLocationUpdates(provider, 1000L, 0f, this)
            locationManager.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        LocationBus.updateLocation(location)
        recordTrack(location)
        recompute()
    }

    /**
     * Paint the square the car is in, so the map fills itself in as it is driven.
     *
     * Only while a trip is running, only while genuinely moving, and only from a fix worth
     * trusting: a parked car with a wandering GPS would otherwise smear a blot over the
     * neighbourhood it is standing in, and a 100 m fix would paint a road it was never on. The last
     * square is remembered so a minute at a red light is one insert the database ignores, not sixty.
     */
    private fun recordTrack(location: Location) {
        // Only while a trip is open. Painting the map is something the driver opts into for a
        // journey rather than a log that runs whenever the engine does: the trip button is already
        // the place where "this drive counts" is said, and a record of everywhere a car has ever
        // been should not accumulate by default.
        if (TripTracker.active.value == null) return
        if (!location.hasSpeed() || location.speed < MOVING_SPEED_MPS) return
        if (location.hasAccuracy() && location.accuracy > TRACK_MAX_ACCURACY_M) return
        val y = TrackCell.cellYof(location.latitude)
        val x = TrackCell.cellXof(location.latitude, location.longitude)
        if (y == lastCellY && x == lastCellX) return
        lastCellY = y
        lastCellX = x
        lifecycleScope.launch { trackRepository.record(location.latitude, location.longitude) }
    }

    /** Re-evaluate which points the car is inside. Runs on every GPS tick AND whenever the
     *  saved-points list changes, so an alert fires immediately (e.g. right after saving). */
    private fun recompute() {
        val loc = lastLocation ?: return
        updateParked(loc)
        // Judged once per parked spell. Deliberately runs even with no blocks at all: that is how
        // deleting the block you're parked on takes its warning and its alarm down with it.
        if (parked && !parkedChecked) checkParkingBlock()
        val now = System.currentTimeMillis()
        val nowInside = mutableSetOf<Long>()
        val nowInfo = mutableSetOf<Long>()
        val distances = mutableMapOf<Long, Int>()

        // Heading is only trustworthy while genuinely moving; when parked/idling we can't tell
        // which way we face, so we skip the direction filter and alert regardless.
        val moving = loc.hasBearing() && loc.hasSpeed() && loc.speed >= MOVING_SPEED_MPS
        val directionAware = Settings.directionAware(this)

        for (p in points) {
            if (!p.alertEnabled) continue // points marked "no alert" are shown on the map but never warn
            val d = GeoUtils.distanceMeters(loc.latitude, loc.longitude, p.lat, p.lng)
            // INFO style — and every POI and charger, which are places you're passing, not hazards:
            // no ring, no beep, no banner. The icon just swells with its details within ~200 m and
            // closes again once you're past. Distance goes in the same map so the label counts down.
            // A radius ring is left to mean one thing only: a camera.
            if (p.infoMode || p.type.popsOnly) {
                if (d <= INFO_DISTANCE_M) {
                    nowInfo.add(p.id)
                    distances[p.id] = d.toInt()
                }
                continue
            }
            // Parked inside the radius: you're not driving at this camera, you're sitting next to
            // it. Muting also clears insideIds, so pulling away re-arms and beeps like a fresh pass.
            if (parked) continue
            if (d > p.radiusM) continue

            // Geometry filters, all of which need a trustworthy heading, so all of them only apply
            // while genuinely moving — and all under the one setting that says "use where I'm
            // pointing to decide what to warn about".
            if (directionAware && moving) {
                val bearingToPoint = GeoUtils.bearingDeg(loc.latitude, loc.longitude, p.lat, p.lng)
                val off = GeoUtils.angleDiffDeg(loc.bearing.toDouble(), bearingToPoint)
                // Behind us: already passed, or coming the other way.
                if (off > AWAY_ANGLE_DEG) continue
                // Beside us: inside the radius but off the line we're driving, which is what a
                // camera on the crossing street looks like. The allowance widens with distance
                // because a bend puts a point genuinely on our road well off the current heading,
                // and missing a real camera is worse than warning about one we'll drive past.
                if (GeoUtils.crossTrackMeters(d, off) > corridorAt(d)) continue
                // The road's own axis, for points saved while driving it. Matched BOTH ways by
                // default — the way home passes the same camera from the other side — unless the
                // driver has marked it as watching one carriageway only.
                val axis = p.headingDeg
                if (axis != null) {
                    val offAxis = GeoUtils.angleDiffDeg(loc.bearing.toDouble(), axis)
                    val onAxis = if (p.oneWay) {
                        offAxis <= AXIS_TOLERANCE_DEG
                    } else {
                        offAxis <= AXIS_TOLERANCE_DEG || offAxis >= 180.0 - AXIS_TOLERANCE_DEG
                    }
                    if (!onAxis) continue
                }
            }

            // A fresh warning for a point warned about minutes ago is the U-turn case: same camera,
            // same stretch of road, nothing new to tell the driver. An hour later on the way home
            // it has expired and warns properly. Points already being warned about are untouched —
            // that's one continuing alert, not a new one.
            val warnedRecently = lastAlertAt[p.id]?.let { now - it < RE_ALERT_COOLDOWN_MS } == true
            if (p.id !in insideIds && warnedRecently) continue

            nowInside.add(p.id)
            distances[p.id] = d.toInt()

            // Beep once on the outside -> inside transition (single beep, no escalation).
            if (p.id !in insideIds) {
                lastAlertAt[p.id] = now
                if (p.alertSound) {
                    Beeper.beep()
                    if (Settings.ttsEnabled(this)) {
                        Speaker.speak("${p.type.label}ข้างหน้า ${roundDist(d)} เมตร")
                    }
                }
            }
        }
        // Expired entries are just noise; drop them in a batch rather than scanning every second.
        if (lastAlertAt.size > COOLDOWN_MAP_LIMIT) {
            lastAlertAt.entries.removeAll { now - it.value >= RE_ALERT_COOLDOWN_MS }
        }

        insideIds = nowInside
        LocationBus.updateActiveAlerts(nowInside)
        LocationBus.updateInfoActive(nowInfo)
        LocationBus.updateAlertDistances(distances)
        updateOverlay()
    }

    /**
     * "Parked" = stopped for [PARKED_AFTER_MS] straight. A red light or a jam is shorter than that,
     * so those still warn; sitting at home/work next to a saved camera goes quiet instead of leaving
     * a banner stuck on screen. The first real movement un-parks immediately.
     */
    private fun updateParked(loc: Location) {
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        // Un-park only on a speed the car can't fake while standing still: a parked GPS drifts by
        // 1–3 km/h, and one such blip must not undo the mute (measured on-device — it did).
        if (kmh >= UNPARK_KMH) {
            stationarySince = 0L
            setParked(false)
            return
        }
        // Crawling (2–10 km/h): neither driving off nor settled — hold whatever state we're in.
        if (kmh >= PARKED_KMH) {
            stationarySince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (stationarySince == 0L) stationarySince = now
        else if (now - stationarySince >= PARKED_AFTER_MS) setParked(true)
    }

    private fun setParked(value: Boolean) {
        if (parked == value) return
        val wasParked = parked
        parked = value
        getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PARKED, value).apply()
        if (value) {
            // The spot is written the moment the car settles, not when a door opens: by then the
            // phone may already be out of the car and drifting away from where it actually stands.
            lastLocation?.let { ParkedSpot.save(this, it.latitude, it.longitude) }
        } else {
            ParkedSpot.clear(this)
            clearParkingState()
        }
        // Standing still for minutes and then driving off is the car being used again, told by the
        // one signal that needs nothing from the head unit: the GPS. On a unit that neither boots
        // nor reports its screen, this is the only thing left that can bring the app back up.
        if (wasParked && !value) autoOpen(Settings.DRIVE_ACTION)
    }

    /**
     * The car has just settled: if it did so on a mapped block, say whether this kerb is the right
     * one today — and if it is, but only until midnight, book tonight's reminder.
     *
     * Only ever runs on the moment of parking. Re-checking every tick would re-notify each time a
     * GPS fix wobbled across the centre line, and the answer can't change while the car is still.
     */
    private fun checkParkingBlock() {
        val loc = lastLocation ?: return
        // The first fix after a restart is whatever the system had lying around, which can be an
        // hour old and a district away — and judging a kerb by it means warning about a street the
        // car isn't on. Wait for a real one; [parkedChecked] stays false so the next tick retries.
        val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        if (fixAgeMs > FIX_FRESH_MS) return
        parkedChecked = true
        val hit = ParkingRules.blockAt(parkingBlocks, loc.latitude, loc.longitude)
        if (hit == null) {
            clearParkingState()
            return
        }
        val (block, near) = hit
        // Which kerb the car is on is a *sign*, and a fix that lands within its own error of the
        // centre line has no trustworthy sign at all. Saying nothing beats telling someone parked
        // legally to move: they'd move to the kerb that really is wrong.
        val ambiguous = maxOf(SIDE_MIN_M, if (loc.hasAccuracy()) loc.accuracy * 0.5f else 0f).toDouble()
        if (near.distanceM < ambiguous) {
            clearParkingState()
            return
        }
        val now = System.currentTimeMillis()
        val state = ParkingRules.stateOf(block, near.side, now)
        val flips = ParkingRules.flipsOvernight(block, near.side, now)
        LocationBus.updateParkedOn(
            LocationBus.ParkedOn(block.id, block.name, near.side, state, flips)
        )

        // Wipe the last verdict before posting this one: re-parking a few metres up the street can
        // turn "wrong kerb" into "fine until midnight", and the old card must not outlive it.
        ParkingAlarm.cancel(this)
        ParkingNotifier.clear(this)
        if (state != ParkingState.ALLOWED) {
            ParkingNotifier.wrongSide(this, block.name, state)
            return
        }
        if (flips && Settings.parkingReminder(this)) {
            ParkingAlarm.schedule(this, block.name, ParkingRules.reminderAt(now))
        }
    }

    /** Driving off ends every parking message: none of them are true about a moving car. */
    private fun clearParkingState() {
        parkedChecked = false
        LocationBus.updateParkedOn(null)
        ParkingAlarm.cancel(this)
        ParkingNotifier.clear(this)
    }

    /** How far off our path a point may sit and still count as "on this road", at distance [d]. */
    private fun corridorAt(d: Double): Double = maxOf(CORRIDOR_MIN_M, d * CORRIDOR_FRACTION)

    /** Round to a spoken-friendly distance (100s far out, 50s mid, 10s close). */
    private fun roundDist(d: Double): Int = when {
        d >= 300 -> (d / 100).toInt() * 100
        d >= 100 -> (d / 50).toInt() * 50
        else -> ((d / 10).toInt() * 10).coerceAtLeast(10)
    }

    /** What the floating card says — the same shape of information as the in-app rail. */
    private data class OverlayContent(
        val type: String,
        val distance: String,
        val name: String,
        val next: String?,
        /** Matches the in-app banner: red camera, green charger, blue saved point. */
        val color: Int
    )

    /** Floating red card drawn over other apps while backgrounded and inside an alert. */
    private fun updateOverlay() {
        val active = insideIds
        val content = simOverlay ?: liveOverlayContent()
        val show = Settings.overlayEnabled(this) &&
            Settings.canDrawOverlays(this) &&
            content != null &&
            !AppState.inForeground.value &&
            // Shared with the in-app rail: one ✕ anywhere silences this alert everywhere.
            (simOverlay != null || active != LocationBus.dismissedIds.value)
        if (!show) {
            hideOverlay()
            return
        }
        showOverlay(content!!)
    }

    private fun liveOverlayContent(): OverlayContent? {
        val active = insideIds
        if (active.isEmpty()) return null
        val dist = LocationBus.alertDistances.value
        val sorted = points.filter { it.id in active }
            .sortedBy { dist[it.id] ?: Int.MAX_VALUE }
        val lead = sorted.firstOrNull() ?: return null
        val leadDistance = dist[lead.id]
        val near = leadDistance != null && leadDistance < AlertFormat.FLOOR_M
        return OverlayContent(
            type = lead.type.label,
            distance = leadDistance?.let { AlertFormat.countdown(it) } ?: "ใกล้จุดเตือน",
            name = lead.name,
            next = sorted.getOrNull(1)?.let { n ->
                "ถัดไป · ${n.name}" + (dist[n.id]?.let { "   $it ม." } ?: "")
            },
            color = (if (near) lead.type.alertColorNear else lead.type.alertColor).toInt()
        )
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    /** Hide the banner for the current alert without opening the app. */
    private fun dismissOverlay() {
        LocationBus.dismissAlerts(insideIds)
        simOverlay = null
        hideOverlay()
    }

    /**
     * Same card as the in-app rail, but built out of plain views because a service has no Compose
     * tree.
     */
    private fun showOverlay(content: OverlayContent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        if (overlayView == null) {
            val typeView = TextView(this).apply {
                setTextColor(0xE0FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                // Keeps the label clear of the ✕ corner hotspot laid over it.
                setPadding(0, dp(4), dp(56), 0)
            }
            // Mirrors the in-app rail's DismissCorner: the tap target is the whole top-right
            // corner of the card, not the glyph, so it can be hit from the driver's seat.
            val dismiss = TextView(this).apply {
                text = "✕"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.TOP or Gravity.END
                setPadding(dp(30), dp(10), dp(14), dp(26))
                setOnClickListener { dismissOverlay() }
            }
            val distanceView = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                includeFontPadding = false
            }
            val nameView = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val nextView = TextView(this).apply {
                setTextColor(0xD8FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            }
            val hint = TextView(this).apply {
                text = "แตะเพื่อเปิดแอป"
                setTextColor(0xB0FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, 0)
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(10), dp(12), dp(14))
                addView(typeView, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(distanceView)
                addView(nameView)
                addView(nextView)
                addView(hint)
            }
            val root = FrameLayout(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
                // Whole card opens the app; only the ✕ corner swallows its own tap.
                setOnClickListener { openApp() }
                addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
                addView(
                    dismiss,
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
                )
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                dp(240),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // Touchable card (tap = open, ✕ = dismiss); touches outside it pass to the app behind.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Mirrors where the rail sits inside the app.
                gravity = Gravity.TOP or Gravity.END
                x = dp(16)
                y = dp(24)
            }
            overlayView = root
            overlayType = typeView
            overlayDistance = distanceView
            overlayName = nameView
            overlayNext = nextView
            runCatching { wm.addView(root, lp) }
        }
        (overlayView?.background as? GradientDrawable)?.setColor(content.color)
        overlayType?.text = content.type
        overlayDistance?.text = content.distance
        overlayName?.text = content.name
        overlayNext?.apply {
            text = content.next.orEmpty()
            visibility = if (content.next == null) View.GONE else View.VISIBLE
        }
    }

    private fun hideOverlay() {
        val v = overlayView ?: return
        overlayView = null
        overlayType = null
        overlayDistance = null
        overlayName = null
        overlayNext = null
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { unregisterReceiver(wakeReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        hideOverlay()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "ตำแหน่ง", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RadarPin")
            .setContentText("กำลังเฝ้าเตือนจุดที่บันทึกไว้")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "location"
        /** How close an INFO point gets before its icon+name pops up (then closes past it). */
        const val INFO_DISTANCE_M = 200.0
        /** ~9 km/h; below this a GPS heading is noise, so every direction filter stands down. */
        const val MOVING_SPEED_MPS = 2.5f
        private const val AWAY_ANGLE_DEG = 75.0    // point is off to the side/behind -> suppress
        private const val CORRIDOR_MIN_M = 40.0    // how far off our path a point may still sit…
        private const val CORRIDOR_FRACTION = 0.2  // …growing with distance, so bends don't go quiet
        private const val AXIS_TOLERANCE_DEG = 50.0 // heading vs the road axis saved with the point
        private const val RE_ALERT_COOLDOWN_MS = 5 * 60_000L // same point, same few minutes = once
        private const val COOLDOWN_MAP_LIMIT = 64  // prune the cooldown table past this many points
        /** Dark this long before a wake counts as "the car was switched off and on again". */
        private const val WAKE_MIN_SLEEP_MS = 2 * 60_000L
        private const val WAKE_DEBOUNCE_MS = 10_000L
        private const val ALIVE_TICK_MS = 60_000L
        private const val PARKED_KMH = 2f          // below this the car is standing still
        private const val UNPARK_KMH = 10f         // above this it has genuinely driven off
        private const val PARKED_AFTER_MS = 120_000L // stopped this long = parked -> mute alerts
        /** Closer than this to a block's centre line and the kerb the car is on is a coin toss. */
        private const val SIDE_MIN_M = 4f
        /** A fix looser than this paints roads the car was never on. */
        private const val TRACK_MAX_ACCURACY_M = 30f
        /** How old a fix may be and still be worth judging a parking spot by. */
        private const val FIX_FRESH_MS = 60_000L
        private const val STATE_PREF = "alert_state"
        private const val KEY_PARKED = "parked"

        private const val ACTION_SIM_OVERLAY = "com.bydmapcam.SIM_OVERLAY"
        private const val ACTION_SIM_CLEAR = "com.bydmapcam.SIM_CLEAR"

        /** Show a demo card, so pressing Home reveals exactly what the driver would see. */
        fun simulateOverlay(context: Context) = send(context, ACTION_SIM_OVERLAY)

        fun clearSimulatedOverlay(context: Context) = send(context, ACTION_SIM_CLEAR)

        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java).setAction(action)
            )
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
