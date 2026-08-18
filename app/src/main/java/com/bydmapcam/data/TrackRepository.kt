package com.bydmapcam.data

import android.content.Context

class TrackRepository(context: Context) {
    private val dao = AppDatabase.get(context).trackDao()

    suspend fun record(lat: Double, lng: Double) =
        dao.insert(TrackCell.of(lat, lng, System.currentTimeMillis()))

    /** Cell centres inside a viewport, as plain points ready to be drawn. */
    suspend fun inBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        limit: Int = MAX_ON_SCREEN
    ): List<GeoPoint> {
        val y0 = TrackCell.cellYof(minLat)
        val y1 = TrackCell.cellYof(maxLat)
        // The longitude grid is sized per latitude band, so the x range is taken at both edges and
        // widened to cover whichever band gives the looser bound.
        val xs = listOf(
            TrackCell.cellXof(minLat, minLng), TrackCell.cellXof(minLat, maxLng),
            TrackCell.cellXof(maxLat, minLng), TrackCell.cellXof(maxLat, maxLng)
        )
        return dao.inBounds(minOf(y0, y1), maxOf(y0, y1), xs.min(), xs.max(), limit)
            .map { TrackCell.centre(it.cellY, it.cellX) }
    }

    /** How much ground has been covered, in km — the row count is already deduplicated. */
    suspend fun coveredKm(): Double = dao.count() * TrackCell.SIZE_M / 1000.0

    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_ON_SCREEN = 12_000
    }
}
