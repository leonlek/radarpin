package com.bydmapcam.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydmapcam.MainActivity
import com.bydmapcam.location.LocationService
import com.bydmapcam.settings.Settings

/**
 * Opens the app by itself when the head unit powers up, if the driver turned that on (default off).
 *
 * Two things happen, in this order, so something useful survives even on a strict ROM:
 *  1. MainActivity is launched. Android 10+ blocks background activity starts unless the app holds
 *     "display over other apps" (SYSTEM_ALERT_WINDOW) — which this app already asks for — so the
 *     window only actually appears when that permission is granted;
 *  2. the location service starts, so a ROM that blocks the window still gets audible alerts. It
 *     goes second because from the top app the start is unambiguously legal (see onReceive).
 *
 * Whatever happens is written down for the settings screen to show, because in a car there is no
 * adb and no logcat: "the app didn't open" has to be split into "the head unit never broadcast"
 * and "it did, and the window was blocked", and nothing else can tell those two apart.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return

        // Traced even when the feature is off: the first question to answer in a car is always
        // whether the head unit broadcasts anything at all when it powers up. Settings shows this.
        if (!Settings.autoStartOnBoot(context)) {
            Settings.recordBootTrace(context, action, Settings.BOOT_OFF)
            return
        }

        // Background-only by choice: on a head unit the screen usually belongs to the radio or a
        // phone projection, and the warnings — beep, spoken name, card over other apps — need no
        // window of ours at all. The service is the app as far as driving is concerned.
        if (!Settings.autoStartShowApp(context)) {
            runCatching { LocationService.start(context) }
            Settings.recordBootTrace(context, action, Settings.BOOT_SERVICE_ONLY)
            return
        }

        // Android 10+ drops a background activity start without "display over other apps" — and it
        // drops it *silently*, no exception — so the permission we hold right now IS the outcome.
        val canShowWindow = Settings.canDrawOverlays(context)
        // The window first, the service second. Android 14+ refuses a location foreground service
        // started from a background broadcast, and asking anyway used to take the whole process
        // down with it — killing the very window this receiver had just opened. Once the activity
        // is up we are the top app, which is the state that start is allowed from, and MainActivity
        // starts the service itself anyway.
        //
        // REORDER_TO_FRONT, not CLEAR_TOP: a ROM that sends BOOT_COMPLETED *and* QUICKBOOT_POWERON
        // gets us here twice, and CLEAR_TOP would tear the activity down and build it again — which
        // means destroying and re-creating the MapLibre surface. That stalls the main thread long
        // enough to ANR on a head unit. Reordering reuses whatever is already there.
        val started = runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.isSuccess
        // Belt and braces for a ROM that blocks the window: on an Android that still permits it,
        // the alerts at least beep with no UI at all. A refusal is handled inside the service.
        runCatching { LocationService.start(context) }
        Settings.recordBootTrace(
            context,
            action,
            when {
                !started -> Settings.BOOT_ERROR
                canShowWindow -> Settings.BOOT_STARTED
                else -> Settings.BOOT_NO_OVERLAY
            }
        )
    }

    private companion object {
        // QUICKBOOT_POWERON is what a lot of Chinese head units send instead of BOOT_COMPLETED.
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
