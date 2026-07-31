package com.bydmapcam.settings

import android.content.Context
import android.provider.Settings as AndroidSettings

/** Global app settings backed by SharedPreferences. */
object Settings {
    private const val PREF = "byd_settings"
    private const val KEY_TTS = "tts_enabled"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_HEADING_UP = "heading_up"
    private const val KEY_DIRECTION_AWARE = "direction_aware"
    private const val KEY_AUTO_BOOT = "auto_start_boot"
    private const val KEY_ME_ICON = "me_icon"

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

    fun headingUp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEADING_UP, true) // default ON (map faces driving direction)

    fun setHeadingUp(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_HEADING_UP, value).apply()

    fun directionAware(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIRECTION_AWARE, true) // default ON (skip points we're driving away from)

    fun setDirectionAware(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DIRECTION_AWARE, value).apply()

    /** Open the app by itself when the head unit boots. */
    fun autoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_BOOT, false) // default OFF

    fun setAutoStartOnBoot(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_BOOT, value).apply()

    fun meIcon(context: Context): MeIcon {
        val name = prefs(context).getString(KEY_ME_ICON, null) ?: return MeIcon.ARROW
        return runCatching { MeIcon.valueOf(name) }.getOrDefault(MeIcon.ARROW)
    }

    fun setMeIcon(context: Context, value: MeIcon) =
        prefs(context).edit().putString(KEY_ME_ICON, value.name).apply()

    fun canDrawOverlays(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)
}
