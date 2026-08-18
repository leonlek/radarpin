package com.bydmapcam.data

import androidx.room.Entity
import kotlin.math.cos
import kotlin.math.floor

/**
 * One forty-metre square of the world that this car has driven through.
 *
 * Roads are kept as a grid rather than as the raw track for one reason: the same commute is driven
 * three hundred times a year, and a track would store it three hundred times while a grid stores it
 * once. The cell coordinates *are* the primary key, so a repeat drive is an insert that the
 * database throws away, and the row count is the honest answer to "how much have I actually
 * covered" — which is the number that makes filling the map in a game rather than a log.
 */
@Entity(tableName = "track_cells", primaryKeys = ["cellY", "cellX"])
data class TrackCell(
    val cellY: Int,
    val cellX: Int,
    val firstSeenAt: Long
) {
    companion object {
        /** Square side. Small enough to trace a soi, big enough that a city fits in a few thousand. */
        const val SIZE_M = 40.0

        private const val M_PER_DEG_LAT = 110_540.0
        private const val M_PER_DEG_LNG = 111_320.0

        private const val LAT_STEP = SIZE_M / M_PER_DEG_LAT

        /**
         * Longitude squares are sized at the latitude band's own centre, so a cell stays roughly
         * square as you drive north — and, more importantly, so the same spot always lands in the
         * same cell no matter which direction you arrived from.
         */
        private fun lngStep(cellY: Int): Double {
            val bandLat = (cellY + 0.5) * LAT_STEP
            return SIZE_M / (M_PER_DEG_LNG * cos(Math.toRadians(bandLat)))
        }

        fun cellYof(lat: Double): Int = floor(lat / LAT_STEP).toInt()

        fun cellXof(lat: Double, lng: Double): Int {
            val y = cellYof(lat)
            return floor(lng / lngStep(y)).toInt()
        }

        fun of(lat: Double, lng: Double, at: Long): TrackCell =
            TrackCell(cellYof(lat), cellXof(lat, lng), at)

        /** Middle of the square, which is where its dot is drawn. */
        fun centre(cellY: Int, cellX: Int): GeoPoint = GeoPoint(
            lat = (cellY + 0.5) * LAT_STEP,
            lng = (cellX + 0.5) * lngStep(cellY)
        )
    }
}
