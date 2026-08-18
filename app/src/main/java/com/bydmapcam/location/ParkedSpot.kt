package com.bydmapcam.location

import android.content.Context
import com.bydmapcam.data.GeoPoint
import kotlin.math.roundToInt

/**
 * Where the car was left.
 *
 * The app already works out when the car has stopped for good — that is what mutes the camera
 * alerts and decides which kerb it is parked on — so remembering the spot costs one write. The
 * value only shows up on the device that walks away with you: on the head unit it is a marker on a
 * map you are sitting in, on the phone it is the way back across a mall car park.
 *
 * Kept in plain preferences rather than the database: it is one row that is overwritten every time
 * and has to survive the process being killed while the driver is inside the shop.
 */
object ParkedSpot {
    private const val PREF = "parked_spot"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_AT = "at"

    /** Closer than this and you can see the car; the reminder card would just be in the way. */
    const val NEAR_M = 60.0

    data class Spot(val point: GeoPoint, val atMillis: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(context: Context, lat: Double, lng: Double) {
        prefs(context).edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LNG, lng.toFloat())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        LocationBus.updateParkedSpot(read(context))
    }

    /** Driving off means the car is under you again; the old spot is a lie from that moment. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        LocationBus.updateParkedSpot(null)
    }

    fun read(context: Context): Spot? {
        val p = prefs(context)
        val at = p.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        return Spot(
            point = GeoPoint(p.getFloat(KEY_LAT, 0f).toDouble(), p.getFloat(KEY_LNG, 0f).toDouble()),
            atMillis = at
        )
    }

    /** Compass word for a bearing — "ทางทิศตะวันออก" reads faster than "94°" while walking. */
    fun compass(bearingDeg: Double): String {
        val names = listOf(
            "เหนือ", "ตะวันออกเฉียงเหนือ", "ตะวันออก", "ตะวันออกเฉียงใต้",
            "ใต้", "ตะวันตกเฉียงใต้", "ตะวันตก", "ตะวันตกเฉียงเหนือ"
        )
        val index = (((bearingDeg % 360.0) + 360.0) % 360.0 / 45.0).roundToInt() % 8
        return names[index]
    }
}
