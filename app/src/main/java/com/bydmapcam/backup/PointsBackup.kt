package com.bydmapcam.backup

import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.PointType
import org.json.JSONArray
import org.json.JSONObject

/** JSON export/import for the saved points (used for local backup / restore). */
object PointsBackup {

    fun toJson(points: List<AlertPoint>): String {
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("type", p.type.name)
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("radiusM", p.radiusM)
                    put("alertEnabled", p.alertEnabled)
                    put("alertSound", p.alertSound)
                    put("createdAt", p.createdAt)
                }
            )
        }
        return JSONObject().apply {
            put("version", 1)
            put("points", arr)
        }.toString(2)
    }

    fun fromJson(json: String): List<AlertPoint> {
        val arr = JSONObject(json).optJSONArray("points") ?: JSONArray()
        val result = ArrayList<AlertPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(
                AlertPoint(
                    id = 0,
                    name = o.optString("name", ""),
                    type = runCatching { PointType.valueOf(o.optString("type", "POI")) }
                        .getOrDefault(PointType.POI),
                    lat = o.getDouble("lat"),
                    lng = o.getDouble("lng"),
                    radiusM = o.optInt("radiusM", 300),
                    alertEnabled = o.optBoolean("alertEnabled", true),
                    alertSound = o.optBoolean("alertSound", true),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }
        return result
    }
}
