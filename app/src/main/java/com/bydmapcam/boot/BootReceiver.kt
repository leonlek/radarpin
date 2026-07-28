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
 *  1. the location service starts — receiving BOOT_COMPLETED exempts us from the
 *     background-foreground-service restriction, so alerts work even with no UI;
 *  2. MainActivity is launched. Android 10+ blocks background activity starts unless the app holds
 *     "display over other apps" (SYSTEM_ALERT_WINDOW) — which this app already asks for — so the
 *     window only actually appears when that permission is granted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (!Settings.autoStartOnBoot(context)) return

        runCatching { LocationService.start(context) }
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
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
