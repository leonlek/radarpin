package com.bydmapcam.offline

import org.maplibre.android.geometry.LatLngBounds

/** Last-known visible map region + zoom, published by the map so the offline
 *  downloader can grab "what's on screen" without holding a reference to the map. */
object MapCamera {
    @Volatile
    var bounds: LatLngBounds? = null

    @Volatile
    var zoom: Double = 0.0
}
