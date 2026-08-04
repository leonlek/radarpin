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
    private const val KEY_BOOT_AT = "boot_last_at"
    private const val KEY_BOOT_ACTION = "boot_last_action"
    private const val KEY_BOOT_RESULT = "boot_last_result"

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

    /**
     * What the boot receiver saw the last time the head unit powered up. A car can't be watched
     * over adb, and every failure mode looks identical from the driver's seat ("the app didn't
     * open"), so the receiver leaves a trace: no trace at all means the screen never broadcast
     * anything, while a trace with [BOOT_NO_OVERLAY] means it did and Android dropped our window.
     */
    data class BootTrace(val atMillis: Long, val action: String, val result: String)

    const val BOOT_OFF = "off"               // broadcast arrived, but the toggle was off
    const val BOOT_STARTED = "started"       // asked for the window while holding the overlay permission
    const val BOOT_NO_OVERLAY = "no_overlay" // asked without it — Android drops the start, silently
    const val BOOT_ERROR = "error"           // startActivity itself threw

    fun bootTrace(context: Context): BootTrace? {
        val p = prefs(context)
        val at = p.getLong(KEY_BOOT_AT, 0L)
        if (at == 0L) return null
        return BootTrace(
            atMillis = at,
            action = p.getString(KEY_BOOT_ACTION, "").orEmpty(),
            result = p.getString(KEY_BOOT_RESULT, "").orEmpty()
        )
    }

    /** commit(), not apply(): the receiver's process is often killed seconds after boot, and a
     *  write that never lands would read as "no broadcast arrived" — the very thing being tested. */
    fun recordBootTrace(context: Context, action: String, result: String) {
        prefs(context).edit()
            .putLong(KEY_BOOT_AT, System.currentTimeMillis())
            .putString(KEY_BOOT_ACTION, action)
            .putString(KEY_BOOT_RESULT, result)
            .commit()
    }

    fun meIcon(context: Context): MeIcon {
        val name = prefs(context).getString(KEY_ME_ICON, null) ?: return MeIcon.ARROW
        return runCatching { MeIcon.valueOf(name) }.getOrDefault(MeIcon.ARROW)
    }

    fun setMeIcon(context: Context, value: MeIcon) =
        prefs(context).edit().putString(KEY_ME_ICON, value.name).apply()

    fun canDrawOverlays(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)
}
