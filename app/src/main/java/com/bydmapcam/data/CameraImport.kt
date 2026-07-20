package com.bydmapcam.data

import android.content.Context
import com.bydmapcam.backup.PointsBackup
import java.net.HttpURLConnection
import java.net.URL

/** Loads the shared speed-camera dataset — from our hosted JSON, falling back to the
 *  copy bundled in assets (so it works offline). Data seeded from OpenStreetMap (ODbL). */
object CameraImport {
    private const val CAMERAS_URL =
        "https://raw.githubusercontent.com/leonlek/radarpin/main/data/cameras_th.json"
    private const val ASSET = "cameras_th.json"

    fun load(context: Context): List<AlertPoint> {
        val json = fetchFromUrl() ?: readAsset(context) ?: return emptyList()
        return runCatching { PointsBackup.fromJson(json) }.getOrDefault(emptyList())
    }

    private fun fetchFromUrl(): String? = runCatching {
        val conn = (URL(CAMERAS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readAsset(context: Context): String? = runCatching {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()
}
