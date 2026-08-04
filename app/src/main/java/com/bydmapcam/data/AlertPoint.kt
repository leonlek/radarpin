package com.bydmapcam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_points")
data class AlertPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PointType,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 500,
    val alertEnabled: Boolean = true,
    val alertSound: Boolean = true,
    /** INFO style: no radius ring; the icon+name pops up within ~200 m, then closes. */
    val infoMode: Boolean = false,
    /** true = came from the shared camera dataset (import); false = added by the user. */
    val imported: Boolean = false,
    /**
     * Which way the car was pointing when this was saved — in effect the road's axis, captured
     * for free at the moment of saving while driving. null when it was added from a standstill or
     * by long-pressing the map, and then the point warns from every direction as it always did.
     */
    val headingDeg: Double? = null,
    /**
     * Warn only when travelling the recorded direction, for a camera that watches one carriageway.
     * Off by default: by default the axis is matched both ways, because the drive home passes the
     * same camera from the opposite side, and missing a real one is worse than one warning extra.
     */
    val oneWay: Boolean = false,
    val createdAt: Long
)
