package com.bydmapcam.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class PointRepository(context: Context) {
    private val dao = AppDatabase.get(context).alertPointDao()

    fun observeAll(): Flow<List<AlertPoint>> = dao.observeAll()
    suspend fun getAll(): List<AlertPoint> = dao.getAll()
    suspend fun add(point: AlertPoint): Long = dao.insert(point)
    suspend fun update(point: AlertPoint) = dao.update(point)
    suspend fun delete(point: AlertPoint) = dao.delete(point)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
