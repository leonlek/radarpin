package com.bydmapcam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One driving trip. km/1% (the headline metric) needs only distance + the SoC drop the driver
 * reads off the dash — no pack capacity, no electricity price. Cost is optional: when a ฿/kWh is
 * given we bridge %→kWh with [BATTERY_KWH].
 */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Double,
    val startSoc: Int,
    val endSoc: Int,
    /** Optional — only set on trips where the driver wanted a ฿/km figure. */
    val pricePerKwh: Double? = null
)

/** Atto 3 Extended Range usable pack (kWh). Only used to turn % into kWh for the optional cost. */
const val BATTERY_KWH = 60.48

/** Battery % consumed (net). <= 0 when the pack didn't drop (regen / typo). */
val Trip.socUsed: Int get() = startSoc - endSoc

/** km per 1% SoC, or null when the pack didn't drop (can't divide). */
val Trip.kmPerPercent: Double? get() = if (socUsed > 0) distanceKm / socUsed else null

/** Projected range on a full 100% charge at this trip's rate. */
val Trip.fullRangeKm: Double? get() = kmPerPercent?.let { it * 100.0 }

/** ฿ for this trip, if a price was entered. */
fun Trip.costBaht(): Double? =
    pricePerKwh?.let { (socUsed.coerceAtLeast(0) / 100.0) * BATTERY_KWH * it }

/** ฿/km, if a price was entered. */
fun Trip.bahtPerKm(): Double? =
    costBaht()?.let { if (distanceKm > 0) it / distanceKm else null }

/**
 * Average km/1% across trips, ignoring ones too noisy to trust: a SoC drop under 3% (the dash's
 * 1% granularity) or under 1 km driven (a barely-moved / GPS-glitch trip).
 */
fun avgKmPerPercent(trips: List<Trip>): Double? {
    val vals = trips.filter { it.socUsed >= 3 && it.distanceKm >= 1.0 }.mapNotNull { it.kmPerPercent }
    return if (vals.isEmpty()) null else vals.average()
}
