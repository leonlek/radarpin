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
    private const val KEY_AUTO_SHOW_APP = "auto_start_show_app"
    private const val KEY_UPDATE_CHECK = "update_check"
    private const val KEY_UPDATE_AT = "update_checked_at"
    private const val KEY_PARKING_LINES = "parking_lines"
    private const val KEY_PARKING_REMIND = "parking_reminder"
    private const val KEY_ME_ICON = "me_icon"
    private const val KEY_BOOT_AT = "boot_last_at"
    private const val KEY_BOOT_ACTION = "boot_last_action"
    private const val KEY_BOOT_RESULT = "boot_last_result"
    private const val KEY_ALIVE_AT = "service_alive_at"
    private const val KEY_PROC_START_AT = "process_start_at"
    private const val KEY_SCREEN_OFF_AT = "screen_off_seen_at"

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

    /** Draw the odd/even parking kerbs along the streets the driver has mapped. */
    fun parkingLines(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARKING_LINES, true) // default ON

    fun setParkingLines(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_PARKING_LINES, value).apply()

    /** Nudge before midnight when the car is parked on a kerb that swaps over. */
    fun parkingReminder(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARKING_REMIND, true) // default ON

    fun setParkingReminder(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_PARKING_REMIND, value).apply()

    /** Look for a newer build when the app opens (once a day at most). */
    fun updateCheck(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPDATE_CHECK, true) // default ON

    fun setUpdateCheck(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_UPDATE_CHECK, value).apply()

    fun updateCheckedAt(context: Context): Long = prefs(context).getLong(KEY_UPDATE_AT, 0L)

    fun recordUpdateCheck(context: Context) =
        prefs(context).edit().putLong(KEY_UPDATE_AT, System.currentTimeMillis()).apply()

    /** Open the app by itself when the head unit boots. */
    fun autoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_BOOT, false) // default OFF

    fun setAutoStartOnBoot(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_BOOT, value).apply()

    /**
     * Whether starting up also puts the app on screen. Off means the alert service runs and nothing
     * else happens: beeps, the spoken warning and the card over other apps all still work, and the
     * driver keeps whatever they had on the screen — which in a car is usually the radio or a phone
     * projection, not us.
     */
    fun autoStartShowApp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SHOW_APP, true) // default ON = what it always did

    fun setAutoStartShowApp(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_SHOW_APP, value).apply()

    /**
     * What the boot receiver saw the last time the head unit powered up. A car can't be watched
     * over adb, and every failure mode looks identical from the driver's seat ("the app didn't
     * open"), so the receiver leaves a trace: no trace at all means the screen never broadcast
     * anything, while a trace with [BOOT_NO_OVERLAY] means it did and Android dropped our window.
     */
    data class BootTrace(val atMillis: Long, val action: String, val result: String)

    /** A head unit that only sleeps never broadcasts a boot, so the screen waking is the other
     *  way we learn the car has been switched on. Recorded through the same trace. */
    const val WAKE_ACTION = "จอตื่นจากพัก"

    /** The last resort, and the only trigger that needs nothing from the ROM: the car drives off. */
    const val DRIVE_ACTION = "รถเริ่มออกตัว"

    const val BOOT_OFF = "off"               // broadcast arrived, but the toggle was off
    const val BOOT_STARTED = "started"       // asked for the window while holding the overlay permission
    const val BOOT_SERVICE_ONLY = "service"  // background-only by choice: the service, no window
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

    /**
     * A heartbeat from the alert service. It answers the question that decides whether waking the
     * app on power-up is even possible: did our process survive the car being off, or does the
     * head unit kill everything — in which case only the ROM's own auto-start list can help.
     */
    fun recordAlive(context: Context) =
        prefs(context).edit().putLong(KEY_ALIVE_AT, System.currentTimeMillis()).apply()

    fun aliveAt(context: Context): Long = prefs(context).getLong(KEY_ALIVE_AT, 0L)

    /** When this process was created. Older than the last power-up = we survived the car being off. */
    fun recordProcessStart(context: Context) =
        prefs(context).edit().putLong(KEY_PROC_START_AT, System.currentTimeMillis()).apply()

    fun processStartAt(context: Context): Long = prefs(context).getLong(KEY_PROC_START_AT, 0L)

    /** Last time Android told us the screen went dark. Never, on a unit that only cuts the panel's
     *  backlight while the system keeps running — which is exactly the case with no signal to wait for. */
    fun recordScreenOff(context: Context) =
        prefs(context).edit().putLong(KEY_SCREEN_OFF_AT, System.currentTimeMillis()).apply()

    fun screenOffSeenAt(context: Context): Long = prefs(context).getLong(KEY_SCREEN_OFF_AT, 0L)

    fun meIcon(context: Context): MeIcon {
        val name = prefs(context).getString(KEY_ME_ICON, null) ?: return MeIcon.ARROW
        return runCatching { MeIcon.valueOf(name) }.getOrDefault(MeIcon.ARROW)
    }

    fun setMeIcon(context: Context, value: MeIcon) =
        prefs(context).edit().putString(KEY_ME_ICON, value.name).apply()

    fun canDrawOverlays(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)
}
