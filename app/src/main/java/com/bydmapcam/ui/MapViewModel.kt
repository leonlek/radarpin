package com.bydmapcam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.CameraImport
import com.bydmapcam.data.PointRepository
import com.bydmapcam.data.PointType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PointRepository(app)

    val points: StateFlow<List<AlertPoint>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun savePoint(
        name: String,
        type: PointType,
        lat: Double,
        lng: Double,
        radiusM: Int,
        alertEnabled: Boolean,
        alertSound: Boolean,
        infoMode: Boolean
    ) {
        viewModelScope.launch {
            repo.add(
                AlertPoint(
                    name = name.ifBlank { type.label },
                    type = type,
                    lat = lat,
                    lng = lng,
                    radiusM = radiusM,
                    alertEnabled = alertEnabled,
                    alertSound = alertSound,
                    infoMode = infoMode,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
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
