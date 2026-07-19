package com.bydmapcam.settings

import android.content.Context
import android.provider.Settings as AndroidSettings

/** Global app settings backed by SharedPreferences. */
object Settings {
    private const val PREF = "byd_settings"
    private const val KEY_TTS = "tts_enabled"
    private const val KEY_OVERLAY = "overlay_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ttsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS, false) // default OFF

    fun setTtsEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_TTS, value).apply()

    fun overlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY, true) // default ON (needs permission)

    fun setOverlayEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_OVERLAY, value).apply()

    fun canDrawOverlays(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)
}
