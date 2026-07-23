package com.bydmapcam.trip

import android.content.Context
import android.location.Location
import com.bydmapcam.location.GeoUtils
import com.bydmapcam.location.LocationBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks the in-flight trip, accumulating GPS distance while active. Process-wide singleton so it
 * survives recomposition / backgrounding (the LocationService keeps the process alive).
 *
 * The active trip is also mirrored to SharedPreferences (a single tiny record, rewritten in place)
 * so it survives the process actually dying — engine off, head-unit reboot, OS kill. On next launch
 * [init] restores it and resumes tracking, flagging [restored] so the UI can ask finish/keep/discard
 * instead of silently continuing. A finished trip is persisted to the DB by the ViewModel; this only
 * holds the live one.
 */
object TripTracker {
    data class Active(
        val startSoc: Int,
        val startTime: Long,
        val distanceKm: Double
    )

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    /** true when the current active trip was restored from disk and the user hasn't acknowledged it. */
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var accumMeters = 0.0
    private var lastPersistMeters = 0.0
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var appContext: Context? = null

    /** Call once at app start. Restores a persisted in-flight trip and resumes tracking, if any. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = appContext!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val startTime = p.getLong(KEY_START_TIME, 0L)
        val startSoc = p.getInt(KEY_START_SOC, -1)
        if (startTime == 0L || startSoc < 0) return
        val dist = Double.fromBits(p.getLong(KEY_DISTANCE_BITS, 0L))
        accumMeters = dist * 1000.0
        lastPersistMeters = accumMeters
        lastLat = Double.NaN
        lastLng = Double.NaN
        _active.value = Active(startSoc, startTime, dist)
        _restored.value = true
        startCollector()
    }

    fun start(startSoc: Int, startTime: Long) {
        accumMeters = 0.0
        lastPersistMeters = 0.0
        lastLat = Double.NaN
        lastLng = Double.NaN
        _active.value = Active(startSoc, startTime, 0.0)
        _restored.value = false
        persist()
        startCollector()
    }

    /** User has seen the restore prompt and chose to keep driving — stop nagging. */
    fun acknowledgeRestore() {
        _restored.value = false
    }

    private fun startCollector() {
        job?.cancel()
        job = scope.launch {
            LocationBus.location.collect { loc -> loc?.let(::onFix) }
        }
    }

    private fun onFix(loc: Location) {
        val a = _active.value ?: return
        if (!lastLat.isNaN()) {
            val seg = GeoUtils.distanceMeters(lastLat, lastLng, loc.latitude, loc.longitude)
            // Count real movement only: gate on GPS speed + a sane segment length, so parked
            // jitter (minDistance=0 → ~1s ticks even stationary) never inflates the distance.
            val moving = loc.hasSpeed() && loc.speed > 0.55f // ~2 km/h
            if (moving && seg in 0.5..300.0) {
                accumMeters += seg
                _active.value = a.copy(distanceKm = accumMeters / 1000.0)
                // Persist at most every ~100 m so a crash loses only the last stretch, not the trip.
                if (accumMeters - lastPersistMeters >= 100.0) {
                    lastPersistMeters = accumMeters
                    persist()
                }
            }
        }
        lastLat = loc.latitude
        lastLng = loc.longitude
    }

    /** Stop tracking and return the final snapshot (null if no trip was active). */
    fun finish(): Active? {
        val a = _active.value
        stopAndClear()
        return a
    }

    fun cancel() {
        stopAndClear()
    }

    private fun stopAndClear() {
        job?.cancel()
        job = null
        _active.value = null
        _restored.value = false
        clearPersisted()
    }

    private fun persist() {
        val a = _active.value ?: return
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()
            ?.putInt(KEY_START_SOC, a.startSoc)
            ?.putLong(KEY_START_TIME, a.startTime)
            ?.putLong(KEY_DISTANCE_BITS, a.distanceKm.toRawBits())
            ?.apply()
    }

    private fun clearPersisted() {
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    private const val PREF = "trip_active"
    private const val KEY_START_SOC = "startSoc"
    private const val KEY_START_TIME = "startTime"
    private const val KEY_DISTANCE_BITS = "distanceBits"
}
