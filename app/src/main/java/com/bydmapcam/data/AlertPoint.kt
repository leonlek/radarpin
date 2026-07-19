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
    val radiusM: Int = 300,
    val alertEnabled: Boolean = true,
    val alertSound: Boolean = true,
    val createdAt: Long
)
