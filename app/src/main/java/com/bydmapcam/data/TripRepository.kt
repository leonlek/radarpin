package com.bydmapcam.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class TripRepository(context: Context) {
    private val dao = AppDatabase.get(context).tripDao()

    fun observeRecent(): Flow<List<Trip>> = dao.observeAll()

    /** Save a finished trip, then trim the log back to the newest [KEEP]. */
    suspend fun save(trip: Trip): Long {
        val id = dao.insert(trip)
        dao.trimTo(KEEP)
        return id
    }

    companion object {
        const val KEEP = 5
    }
}
