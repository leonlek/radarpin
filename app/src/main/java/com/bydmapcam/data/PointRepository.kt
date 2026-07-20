package com.bydmapcam.data

import android.content.Context
import com.bydmapcam.location.GeoUtils
import kotlinx.coroutines.flow.Flow

class PointRepository(context: Context) {
    private val dao = AppDatabase.get(context).alertPointDao()

    fun observeAll(): Flow<List<AlertPoint>> = dao.observeAll()
    suspend fun getAll(): List<AlertPoint> = dao.getAll()
    suspend fun add(point: AlertPoint): Long = dao.insert(point)
    suspend fun update(point: AlertPoint) = dao.update(point)
    suspend fun delete(point: AlertPoint) = dao.delete(point)
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** Insert points, skipping any within ~25 m of an existing one (so re-imports don't duplicate). */
    suspend fun importPoints(points: List<AlertPoint>): Int {
        val existing = dao.getAll().toMutableList()
        var added = 0
        for (p in points) {
            val dup = existing.any { GeoUtils.distanceMeters(it.lat, it.lng, p.lat, p.lng) < 25.0 }
            if (!dup) {
                val toAdd = p.copy(id = 0, createdAt = System.currentTimeMillis())
                dao.insert(toAdd)
                existing.add(toAdd)
                added++
            }
        }
        return added
    }
}
