package com.bydmapcam.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY endTime DESC")
    fun observeAll(): Flow<List<Trip>>

    @Insert
    suspend fun insert(trip: Trip): Long

    /** Keep only the newest [keep] trips; drop the rest so the log never grows without bound. */
    @Query("DELETE FROM trips WHERE id NOT IN (SELECT id FROM trips ORDER BY endTime DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
