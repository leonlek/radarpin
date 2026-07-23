package com.bydmapcam.location

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory bridge between the LocationService (producer) and the UI (consumer). */
object LocationBus {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    /** ids of standard-alert points the car is currently inside the radius of. */
    private val _activeAlertIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeAlertIds: StateFlow<Set<Long>> = _activeAlertIds

    /** ids of INFO-style points the car is currently within ~200 m of (icon pops up). */
    private val _infoActiveIds = MutableStateFlow<Set<Long>>(emptySet())
    val infoActiveIds: StateFlow<Set<Long>> = _infoActiveIds

    /** Live distance (meters, rounded) to each currently-active alert point, for the countdown display. */
    private val _alertDistances = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val alertDistances: StateFlow<Map<Long, Int>> = _alertDistances

    fun updateLocation(loc: Location) {
        _location.value = loc
    }

    fun updateActiveAlerts(ids: Set<Long>) {
        _activeAlertIds.value = ids
    }

    fun updateInfoActive(ids: Set<Long>) {
        _infoActiveIds.value = ids
    }

    fun updateAlertDistances(distances: Map<Long, Int>) {
        _alertDistances.value = distances
    }
}
