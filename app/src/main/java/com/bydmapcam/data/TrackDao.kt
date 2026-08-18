package com.bydmapcam.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackDao {
    /** A cell already driven is not news: IGNORE is what makes the same commute cost nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cell: TrackCell)

    @Query("SELECT COUNT(*) FROM track_cells")
    suspend fun count(): Int

    /**
     * Only what the screen can show. The primary key is (cellY, cellX), so this is an index range
     * scan however much has been driven, and the cap keeps one zoomed-out gesture from building a
     * hundred thousand map features.
     */
    @Query(
        "SELECT * FROM track_cells WHERE cellY BETWEEN :y0 AND :y1 AND cellX BETWEEN :x0 AND :x1 " +
            "LIMIT :limit"
    )
    suspend fun inBounds(y0: Int, y1: Int, x0: Int, x1: Int, limit: Int): List<TrackCell>

    @Query("DELETE FROM track_cells")
    suspend fun clear()
}
