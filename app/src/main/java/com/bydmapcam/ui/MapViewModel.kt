package com.bydmapcam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.CameraImport
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.ParkingRepository
import com.bydmapcam.data.PointRepository
import com.bydmapcam.data.PointType
import com.bydmapcam.data.Trip
import com.bydmapcam.data.TripRepository
import com.bydmapcam.trip.TripTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PointRepository(app)
    private val tripRepo = TripRepository(app)
    private val parkingRepo = ParkingRepository(app)

    val points: StateFlow<List<AlertPoint>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parkingBlocks: StateFlow<List<ParkingBlock>> =
        parkingRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTrips: StateFlow<List<Trip>> =
        tripRepo.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startTrip(startSoc: Int) = TripTracker.start(startSoc, System.currentTimeMillis())

    fun cancelTrip() = TripTracker.cancel()

    /** Record the % battery the driver read off the dash mid-trip (drives the live km/1%). */
    fun setTripSoc(pct: Int) = TripTracker.setSoc(pct)

    /** Finish the active trip: snapshot distance/start-SoC from the tracker, save, report the row back. */
    fun finishTrip(endSoc: Int, pricePerKwh: Double?, onSaved: (Trip) -> Unit) {
        val a = TripTracker.finish() ?: return
        val trip = Trip(
            startTime = a.startTime,
            endTime = System.currentTimeMillis(),
            distanceKm = a.distanceKm,
            startSoc = a.startSoc,
            endSoc = endSoc,
            pricePerKwh = pricePerKwh
        )
        viewModelScope.launch {
            tripRepo.save(trip)
            onSaved(trip)
        }
    }

    fun savePoint(form: PointFormResult, lat: Double, lng: Double, headingDeg: Double?) {
        viewModelScope.launch {
            repo.add(
                AlertPoint(
                    name = form.name.ifBlank { form.type.label },
                    type = form.type,
                    lat = lat,
                    lng = lng,
                    radiusM = form.radiusM,
                    alertEnabled = form.alertEnabled,
                    alertSound = form.alertSound,
                    infoMode = form.infoMode,
                    headingDeg = headingDeg,
                    oneWay = form.oneWay,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Save a freshly drawn block, or the same block again with edited rules. */
    fun saveParkingBlock(pending: PendingBlock, form: ParkingFormResult) {
        viewModelScope.launch {
            val existing = pending.existing
            if (existing == null) {
                parkingRepo.add(
                    ParkingBlock(
                        name = form.name,
                        path = pending.path,
                        leftRule = form.leftRule,
                        rightRule = form.rightRule,
                        banFromMin = form.banFromMin,
                        banToMin = form.banToMin,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else {
                parkingRepo.update(
                    existing.copy(
                        name = form.name,
                        leftRule = form.leftRule,
                        rightRule = form.rightRule,
                        banFromMin = form.banFromMin,
                        banToMin = form.banToMin
                    )
                )
            }
        }
    }

    fun deleteParkingBlock(block: ParkingBlock) {
        viewModelScope.launch { parkingRepo.delete(block) }
    }

    fun delete(point: AlertPoint) {
        viewModelScope.launch { repo.delete(point) }
    }

    fun deleteMany(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { repo.deleteByIds(ids) }
    }

    fun updatePoint(point: AlertPoint) {
        viewModelScope.launch { repo.update(point) }
    }

    /** Download + merge the shared speed-camera dataset; reports how many new points were added. */
    fun importCameras(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val points = withContext(Dispatchers.IO) { CameraImport.load(app) }
            val count = repo.importPoints(points)
            onResult(count)
        }
    }
}
