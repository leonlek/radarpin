package com.bydmapcam.offline

import android.content.Context
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Thin wrapper over MapLibre's offline API. Downloads the *current viewport* of the
 * OpenFreeMap style for offline use. Zoom range is fixed to street-navigation levels;
 * the tile-count limit keeps a zoomed-out (huge-area) download from ballooning storage.
 *
 * All callbacks are delivered on the main thread by MapLibre, so they can drive Compose state.
 */
object OfflineMaps {
    // Must match the style the live map renders, or offline tiles won't be reused.
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    private const val MIN_ZOOM = 10.0
    private const val MAX_ZOOM = 15.0
    // ~20k tiles caps a single region at a few hundred MB worst-case; a district is far less.
    private const val TILE_LIMIT = 20_000L

    data class RegionInfo(val region: OfflineRegion, val name: String, val sizeBytes: Long)

    private fun metaBytes(name: String): ByteArray =
        JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8)

    private fun readName(meta: ByteArray?): String =
        runCatching { JSONObject(String(meta ?: ByteArray(0), Charsets.UTF_8)).optString("name") }
            .getOrDefault("").ifBlank { "พื้นที่" }

    /** List downloaded regions with their on-disk size (resolves each region's status async). */
    fun list(context: Context, onResult: (List<RegionInfo>) -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    val arr = regions?.toList() ?: emptyList()
                    if (arr.isEmpty()) { onResult(emptyList()); return }
                    val out = ArrayList<RegionInfo>()
                    var remaining = arr.size
                    fun done() { remaining--; if (remaining == 0) onResult(out.toList()) }
                    for (r in arr) {
                        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                val size = status?.completedResourceSize ?: 0L
                                out.add(RegionInfo(r, readName(r.metadata), size))
                                done()
                            }
                            override fun onError(error: String?) { done() }
                        })
                    }
                }
                override fun onError(error: String) { onResult(emptyList()) }
            }
        )
    }

    /** Start downloading [bounds] (the current viewport). [onRegionReady] hands back the region
     *  so the caller can cancel; [onProgress] fires repeatedly with fraction + bytes so far. */
    fun startDownload(
        context: Context,
        bounds: LatLngBounds,
        name: String,
        pixelRatio: Float,
        onRegionReady: (OfflineRegion) -> Unit,
        onProgress: (fraction: Float, bytes: Long) -> Unit,
        onComplete: (bytes: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val mgr = OfflineManager.getInstance(context)
        mgr.setOfflineMapboxTileCountLimit(TILE_LIMIT)
        val def = OfflineTilePyramidRegionDefinition(STYLE_URL, bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio)
        mgr.createOfflineRegion(def, metaBytes(name), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                onRegionReady(region)
                var finished = false
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (finished) return
                        val frac = if (status.requiredResourceCount > 0) {
                            (status.completedResourceCount.toDouble() / status.requiredResourceCount)
                                .toFloat().coerceIn(0f, 1f)
                        } else 0f
                        onProgress(frac, status.completedResourceSize)
                        if (status.isComplete) {
                            finished = true
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            onComplete(status.completedResourceSize)
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        if (finished) return
                        finished = true
                        onError(error.reason ?: error.message)
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        if (finished) return
                        finished = true
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {}
                            override fun onError(error: String) {}
                        })
                        onError("พื้นที่ใหญ่เกินไป — ลองซูมเข้าอีกนิดแล้วโหลดใหม่")
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) { onError(error) }
        })
    }

    fun delete(region: OfflineRegion, onDone: () -> Unit) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { onDone() }
            override fun onError(error: String) { onDone() }
        })
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
