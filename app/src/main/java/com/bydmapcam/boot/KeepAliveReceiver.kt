package com.bydmapcam.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.bydmapcam.MainActivity
import com.bydmapcam.location.AppState
import com.bydmapcam.location.LocationService
import com.bydmapcam.settings.Settings

/**
 * Brings the alert service back after the head unit has killed it.
 *
 * The car's own report showed the shape of the problem: the app opens itself perfectly when it is
 * still running (a screen waking is enough), but a long stop leaves nothing alive to notice the car
 * being used again — and no boot broadcast ever arrives to start us either. An alarm is held by the
 * system rather than by us, so it outlives the process that scheduled it and can put it back.
 *
 * Inexact on purpose: exact alarms need a permission and buy nothing here, since a quarter of an
 * hour either way makes no difference to being alive before the next drive. A force-stop clears
 * alarms along with everything else, and that case is beyond anything the app can do — the head
 * unit's own auto-start list is the only remaining lever.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // A fresh process has never had the service up, which is how a resurrection is told from
        // the ordinary tick where everything is already running.
        val resurrected = !LocationService.isRunning
        runCatching { LocationService.start(context) }
        schedule(context)
        if (!resurrected) return

        // Android 14 lets a background-started service go foreground but refuses it location, so a
        // resurrection alone leaves a service that has to stop itself again. Being on screen is
        // what makes it legal — and if the panel is lit, the car is in use and the driver wanted
        // this app up anyway. A dark screen means a parked car: come back quietly and wait for it.
        if (!Settings.autoStartOnBoot(context)) return
        if (AppState.inForeground.value) return
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!screenOn) return
        val opened = runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.isSuccess
        Settings.recordBootTrace(
            context,
            Settings.REVIVE_ACTION,
            when {
                !opened -> Settings.BOOT_ERROR
                Settings.canDrawOverlays(context) -> Settings.BOOT_STARTED
                else -> Settings.BOOT_NO_OVERLAY
            }
        )
    }

    companion object {
        private const val ACTION = "com.bydmapcam.KEEP_ALIVE"
        private const val REQUEST = 2001
        private const val INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

        /** Safe to call repeatedly — the pending intent is replaced, not stacked. */
        fun schedule(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                manager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    INTERVAL_MS,
                    pendingIntent(context)
                )
            }
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
