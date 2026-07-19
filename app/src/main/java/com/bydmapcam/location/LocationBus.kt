package com.bydmapcam.location

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory bridge between the LocationService (producer) and the UI (consumer). */
object LocationBus {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    /** ids of points the car is currently inside the alert radius of. */
    private val _activeAlertIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeAlertIds: StateFlow<Set<Long>> = _activeAlertIds

    fun updateLocation(loc: Location) {
        _location.value = loc
    }

    fun updateActiveAlerts(ids: Set<Long>) {
        _activeAlertIds.value = ids
    }
}
