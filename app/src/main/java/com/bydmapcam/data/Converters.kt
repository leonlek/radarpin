package com.bydmapcam.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun toType(value: String): PointType =
        runCatching { PointType.valueOf(value) }.getOrDefault(PointType.POI)

    @TypeConverter
    fun fromType(type: PointType): String = type.name

    @TypeConverter
    fun toSideRule(value: String): SideRule =
        runCatching { SideRule.valueOf(value) }.getOrDefault(SideRule.NEVER)

    @TypeConverter
    fun fromSideRule(rule: SideRule): String = rule.name

    /**
     * "lat,lng;lat,lng" — a plain string rather than JSON because that is all a polyline is, and
     * a malformed coordinate should drop that one vertex rather than take the whole row with it.
     */
    @TypeConverter
    fun toPath(value: String): List<GeoPoint> =
        value.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat, lng)
        }

    @TypeConverter
    fun fromPath(path: List<GeoPoint>): String =
        path.joinToString(";") { "${it.lat},${it.lng}" }
}
