package com.bydmapcam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertPointDao {
    @Query("SELECT * FROM alert_points ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertPoint>>

    @Query("SELECT * FROM alert_points")
    suspend fun getAll(): List<AlertPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: AlertPoint): Long

    @Update
    suspend fun update(point: AlertPoint)

    @Delete
    suspend fun delete(point: AlertPoint)

    @Query("DELETE FROM alert_points WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM alert_points WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
