package com.bydmapcam.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ParkingRepository(context: Context) {
    private val dao = AppDatabase.get(context).parkingBlockDao()

    fun observeAll(): Flow<List<ParkingBlock>> = dao.observeAll()
    suspend fun getAll(): List<ParkingBlock> = dao.getAll()
    suspend fun add(block: ParkingBlock): Long = dao.insert(block)
    suspend fun update(block: ParkingBlock) = dao.update(block)
    suspend fun delete(block: ParkingBlock) = dao.delete(block)
}
