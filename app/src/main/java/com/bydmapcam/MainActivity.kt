package com.bydmapcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bydmapcam.location.AppState
import com.bydmapcam.location.LocationService
import com.bydmapcam.settings.Settings
import com.bydmapcam.ui.MapScreen
import com.bydmapcam.ui.theme.BydMapCamTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) LocationService.start(this)
            maybeRequestOverlayPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BydMapCamTheme {
                MapScreen()
            }
        }
        ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        AppState.inForeground.value = true
    }

    override fun onStop() {
        super.onStop()
        AppState.inForeground.value = false
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            LocationService.start(this)
            maybeRequestOverlayPermission()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** The overlay banner is on by default but needs the special "display over other apps"
     *  permission. Ask for it once (after location) so it actually works while backgrounded. */
    private fun maybeRequestOverlayPermission() {
        if (!Settings.overlayEnabled(this) || Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("byd_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("overlay_asked", false)) return
        prefs.edit().putBoolean("overlay_asked", true).apply()
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
