package com.bydmapcam

import android.app.Application
import com.bydmapcam.alert.Speaker
import com.bydmapcam.settings.Settings
import com.bydmapcam.trip.TripTracker
import org.maplibre.android.MapLibre

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Stamped here and nowhere else: this is the moment the process was created, which is the
        // only way to tell "we were running the whole time the car was off" from "the head unit
        // killed us and this is a brand new process that started when the app was tapped".
        Settings.recordProcessStart(this)
        // Must be called before any MapView is created.
        MapLibre.getInstance(this)
        // Warm up TTS so voice alerts are ready when enabled.
        Speaker.init(this)
        // Restore an in-flight trip left over from a previous run (engine off before "จบ").
        TripTracker.init(this)
    }
}
