package com.bydmapcam

import android.app.Application
import com.bydmapcam.alert.Speaker
import com.bydmapcam.trip.TripTracker
import org.maplibre.android.MapLibre

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must be called before any MapView is created.
        MapLibre.getInstance(this)
        // Warm up TTS so voice alerts are ready when enabled.
        Speaker.init(this)
        // Restore an in-flight trip left over from a previous run (engine off before "จบ").
        TripTracker.init(this)
    }
}
