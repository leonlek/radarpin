package com.bydmapcam.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun toType(value: String): PointType =
        runCatching { PointType.valueOf(value) }.getOrDefault(PointType.POI)

    @TypeConverter
    fun fromType(type: PointType): String = type.name
}
