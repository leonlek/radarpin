package com.bydmapcam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ParkingBlockDao {
    @Query("SELECT * FROM parking_blocks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ParkingBlock>>

    @Query("SELECT * FROM parking_blocks")
    suspend fun getAll(): List<ParkingBlock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(block: ParkingBlock): Long

    @Update
    suspend fun update(block: ParkingBlock)

    @Delete
    suspend fun delete(block: ParkingBlock)

    @Query("DELETE FROM parking_blocks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
