package com.bydmapcam.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.TextView
import com.bydmapcam.MainActivity
import com.bydmapcam.R
import com.bydmapcam.alert.AlertFormat
import com.bydmapcam.alert.Beeper
import com.bydmapcam.alert.Speaker
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.PointRepository
import com.bydmapcam.data.PointType
import com.bydmapcam.settings.Settings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Foreground service that tracks GPS and beeps when the car enters a saved point's radius. */
class LocationService : LifecycleService(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var repository: PointRepository

    @Volatile
    private var points: List<AlertPoint> = emptyList()
    private var insideIds: Set<Long> = emptySet()
    private var lastLocation: Location? = null
    private var overlayView: View? = null
    private var overlayType: TextView? = null
    private var overlayDistance: TextView? = null
    private var overlayName: TextView? = null
    private var overlayNext: TextView? = null
    private var overlayDismissedFor: Set<Long> = emptySet()

    /** Fake card pushed in from the simulator so the over-other-apps look can be checked at a desk. */
    @Volatile
    private var simOverlay: OverlayContent? = null

    /** When the car first stopped in the current stretch (0 = it's moving). */
    private var stationarySince = 0L

    /** Parked inside an alert zone → alerts muted until the car drives off again. */
    private var parked = false

    override fun onCreate() {
        super.onCreate()
        repository = PointRepository(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Start out parked if that's how we were left: otherwise re-opening the app while parked
        // inside a camera's radius beeps + banners all over again every single time.
        parked = getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).getBoolean(KEY_PARKED, false)

        repository.observeAll()
            .onEach { points = it; recompute() }
            .launchIn(lifecycleScope)

        // Show/hide the over-other-apps overlay as our own UI comes and goes.
        AppState.inForeground
            .onEach { updateOverlay() }
            .launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
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

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
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
        recompute()
    }

    /** Re-evaluate which points the car is inside. Runs on every GPS tick AND whenever the
     *  saved-points list changes, so an alert fires immediately (e.g. right after saving). */
    private fun recompute() {
        val loc = lastLocation ?: return
        updateParked(loc)
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
            // INFO style — and every POI, which is a place you're passing, not a hazard: no ring,
            // no beep, no banner. The icon just swells with its details within ~200 m and closes
            // again once you're past. Distance goes in the same map so the label can count down.
            if (p.infoMode || p.type == PointType.POI) {
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

            // Direction filter: suppress points we're clearly driving AWAY from (behind us —
            // already passed, or travelling the opposite way). Only applies while moving.
            if (directionAware && moving) {
                val bearingToPoint = GeoUtils.bearingDeg(loc.latitude, loc.longitude, p.lat, p.lng)
                val off = GeoUtils.angleDiffDeg(loc.bearing.toDouble(), bearingToPoint)
                if (off > AWAY_ANGLE_DEG) continue
            }

            nowInside.add(p.id)
            distances[p.id] = d.toInt()

            // Beep once on the outside -> inside transition (single beep, no escalation).
            if (p.id !in insideIds && p.alertSound) {
                Beeper.beep()
                if (Settings.ttsEnabled(this)) {
                    Speaker.speak("${p.type.label}ข้างหน้า ${roundDist(d)} เมตร")
                }
            }
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
        parked = value
        getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PARKED, value).apply()
    }

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
        if (active.isEmpty()) overlayDismissedFor = emptySet()
        val content = simOverlay ?: liveOverlayContent()
        val show = Settings.overlayEnabled(this) &&
            Settings.canDrawOverlays(this) &&
            content != null &&
            !AppState.inForeground.value &&
            (simOverlay != null || active != overlayDismissedFor)
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
        overlayDismissedFor = insideIds
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
            }
            val dismiss = TextView(this).apply {
                text = "✕"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(16), 0, dp(4), dp(4))
                setOnClickListener { dismissOverlay() }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(
                    typeView,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(dismiss)
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
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(10), dp(12), dp(14))
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
                // Whole card opens the app; only the ✕ swallows its own tap.
                setOnClickListener { openApp() }
                addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(distanceView)
                addView(nameView)
                addView(nextView)
                addView(hint)
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
        private const val MOVING_SPEED_MPS = 2.5f  // ~9 km/h; below this, heading is unreliable
        private const val AWAY_ANGLE_DEG = 115.0   // point is behind us -> driving away -> suppress
        private const val PARKED_KMH = 2f          // below this the car is standing still
        private const val UNPARK_KMH = 10f         // above this it has genuinely driven off
        private const val PARKED_AFTER_MS = 120_000L // stopped this long = parked -> mute alerts
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
