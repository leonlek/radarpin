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

    /**
     * The alert the driver has waved away with ✕, held here rather than in either UI because the
     * in-app rail and the over-other-apps card are one alert seen from two places. Dismissing the
     * rail and then meeting the same warning again on the next press of Home was the bug this
     * fixes. Being the exact active set, it stops matching as soon as the warned-about points
     * change, which is what re-arms the banner for the next camera.
     */
    private val _dismissedIds = MutableStateFlow<Set<Long>>(emptySet())
    val dismissedIds: StateFlow<Set<Long>> = _dismissedIds

    /**
     * What the block the car has just parked on says about the kerb it is standing on. Set once,
     * when the car settles — parking is a decision that is made once and then done, so this is not
     * something to recompute per GPS tick — and cleared the moment it drives off again.
     */
    private val _parkedOn = MutableStateFlow<ParkedOn?>(null)
    val parkedOn: StateFlow<ParkedOn?> = _parkedOn

    /** [blockId] with the kerb the car is on and that kerb's verdict when it stopped. */
    data class ParkedOn(
        val blockId: Long,
        val blockName: String,
        val side: com.bydmapcam.data.Side,
        val state: com.bydmapcam.parking.ParkingState,
        /** True when this kerb is legal now but not after midnight. */
        val flipsOvernight: Boolean
    )

    fun updateParkedOn(value: ParkedOn?) {
        _parkedOn.value = value
    }

    /** Where the car was left standing, for the walk back to it. */
    private val _parkedSpot = MutableStateFlow<ParkedSpot.Spot?>(null)
    val parkedSpot: StateFlow<ParkedSpot.Spot?> = _parkedSpot

    fun updateParkedSpot(value: ParkedSpot.Spot?) {
        _parkedSpot.value = value
    }

    fun updateLocation(loc: Location) {
        _location.value = loc
    }

    fun updateActiveAlerts(ids: Set<Long>) {
        // Compared first so an unchanged set doesn't wipe a dismissal on every GPS tick — which
        // would also undo the simulator's, where the real active set is empty the whole time.
        if (_activeAlertIds.value == ids) return
        _activeAlertIds.value = ids
        // Out of every zone: forget the ✕ so the next one warns properly.
        if (ids.isEmpty()) _dismissedIds.value = emptySet()
    }

    /** Silence the current alert in both places at once. [ids] is the active set being waved away. */
    fun dismissAlerts(ids: Set<Long>) {
        _dismissedIds.value = ids
    }

    fun updateInfoActive(ids: Set<Long>) {
        _infoActiveIds.value = ids
    }

    fun updateAlertDistances(distances: Map<Long, Int>) {
        _alertDistances.value = distances
    }
}
