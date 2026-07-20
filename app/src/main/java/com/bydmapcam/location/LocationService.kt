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
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.bydmapcam.MainActivity
import com.bydmapcam.R
import com.bydmapcam.alert.Beeper
import com.bydmapcam.alert.Speaker
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.PointRepository
import com.bydmapcam.settings.Settings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Foreground service that tracks GPS and beeps when the car enters a saved point's radius. */
class LocationService : LifecycleService(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var repository: PointRepository

    @Volatile
    private var points: List<AlertPoint> = emptyList()
    private var insideIds: Set<Long> = emptySet()
    // Per-point proximity tier already announced (0=far, 1=near, 2=imminent); rearms on exit.
    private val beepTier: MutableMap<Long, Int> = mutableMapOf()
    private var lastLocation: Location? = null
    private var overlayView: View? = null
    private var overlayLabel: TextView? = null
    private var overlayDismissedFor: Set<Long> = emptySet()

    override fun onCreate() {
        super.onCreate()
        repository = PointRepository(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

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
            if (p.infoMode) {
                // INFO: no beep/banner — just pops the icon up within ~100 m.
                if (d <= INFO_DISTANCE_M) nowInfo.add(p.id)
                continue
            }
            if (d > p.radiusM) continue

            // Direction filter: suppress points we're clearly driving AWAY from (behind us —
            // already passed, or travelling the opposite way). Only applies while moving.
            if (directionAware && moving) {
                val bearingToPoint = GeoUtils.bearingDeg(loc.latitude, loc.longitude, p.lat, p.lng)
                val off = GeoUtils.angleDiffDeg(loc.bearing.toDouble(), bearingToPoint)
                if (off > AWAY_ANGLE_DEG) {
                    beepTier.remove(p.id) // rearm so a genuine re-approach beeps again
                    continue
                }
            }

            nowInside.add(p.id)
            distances[p.id] = d.toInt()

            // Escalating alert: beep more as the tier rises (0 -> 1 -> 2 while distance shrinks).
            if (p.alertSound) {
                val tier = tierFor(d)
                val prev = beepTier[p.id] ?: -1
                if (tier > prev) {
                    Beeper.beep(count = tier + 1)
                    if (Settings.ttsEnabled(this)) Speaker.speak(ttsFor(p, d, tier))
                }
                beepTier[p.id] = tier
            }
        }
        // Forget tiers for points we've left, so the next approach re-arms the escalation.
        beepTier.keys.retainAll(nowInside)

        insideIds = nowInside
        LocationBus.updateActiveAlerts(nowInside)
        LocationBus.updateInfoActive(nowInfo)
        LocationBus.updateAlertDistances(distances)
        updateOverlay()
    }

    /** Proximity tier: 0 = far (just entered), 1 = near, 2 = imminent (right on top of it). */
    private fun tierFor(d: Double): Int = when {
        d <= IMMINENT_M -> 2
        d <= NEAR_M -> 1
        else -> 0
    }

    private fun ttsFor(p: AlertPoint, d: Double, tier: Int): String = when (tier) {
        2 -> "ระวัง ${p.type.label}"
        1 -> "${p.type.label} อีก ${roundDist(d)} เมตร"
        else -> "${p.type.label}ข้างหน้า ${roundDist(d)} เมตร"
    }

    /** Round to a spoken-friendly distance (100s far out, 50s mid, 10s close). */
    private fun roundDist(d: Double): Int = when {
        d >= 300 -> (d / 100).toInt() * 100
        d >= 100 -> (d / 50).toInt() * 50
        else -> ((d / 10).toInt() * 10).coerceAtLeast(10)
    }

    /** Floating red banner drawn over other apps while backgrounded and inside an alert. */
    private fun updateOverlay() {
        val active = insideIds
        if (active.isEmpty()) overlayDismissedFor = emptySet()
        val show = Settings.overlayEnabled(this) &&
            Settings.canDrawOverlays(this) &&
            active.isNotEmpty() &&
            !AppState.inForeground.value &&
            active != overlayDismissedFor
        if (!show) {
            hideOverlay()
            return
        }
        val dist = LocationBus.alertDistances.value
        val names = points.filter { it.id in active }
            .take(3)
            .joinToString("\n") { p ->
                val dm = dist[p.id]
                if (dm != null) "• ${p.name} — อีก $dm ม." else "• ${p.name} (${p.type.label})"
            }
        showOverlay("⚠ ใกล้จุดเตือน — แตะเพื่อเปิดแอป\n$names")
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
        hideOverlay()
    }

    private fun showOverlay(message: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        if (overlayView == null) {
            val label = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                // Cap the width so it stays a compact card instead of a full-screen bar
                // (matters most on the car's wide landscape screen).
                maxWidth = dp(340)
                setOnClickListener { openApp() }
            }
            val dismiss = TextView(this).apply {
                text = "✕"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                setPadding(dp(14), dp(2), dp(6), dp(2))
                setOnClickListener { dismissOverlay() }
            }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(0xF0E53935.toInt())
                    cornerRadius = dp(18).toFloat()
                }
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(dismiss, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // Touchable card (tap = open, ✕ = dismiss); touches outside it pass to the app behind.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(24)
            }
            overlayView = root
            overlayLabel = label
            runCatching { wm.addView(root, lp) }
        }
        overlayLabel?.text = message
    }

    private fun hideOverlay() {
        val v = overlayView ?: return
        overlayView = null
        overlayLabel = null
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
        private const val INFO_DISTANCE_M = 100.0
        private const val MOVING_SPEED_MPS = 2.5f  // ~9 km/h; below this, heading is unreliable
        private const val AWAY_ANGLE_DEG = 115.0   // point is behind us -> driving away -> suppress
        private const val NEAR_M = 150.0
        private const val IMMINENT_M = 60.0

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
