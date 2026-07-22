package com.bydmapcam.trip

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
 * survives recomposition / backgrounding (the LocationService keeps the process alive). It holds
 * only the live trip; persisting a finished trip is the ViewModel/repository's job.
 */
object TripTracker {
    data class Active(
        val startSoc: Int,
        val startTime: Long,
        val distanceKm: Double
    )

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var accumMeters = 0.0
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN

    fun start(startSoc: Int, startTime: Long) {
        job?.cancel()
        accumMeters = 0.0
        lastLat = Double.NaN
        lastLng = Double.NaN
        _active.value = Active(startSoc, startTime, 0.0)
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
            }
        }
        lastLat = loc.latitude
        lastLng = loc.longitude
    }

    /** Stop tracking and return the final snapshot (null if no trip was active). */
    fun finish(): Active? {
        val a = _active.value
        job?.cancel()
        job = null
        _active.value = null
        return a
    }

    fun cancel() {
        job?.cancel()
        job = null
        _active.value = null
    }
}
