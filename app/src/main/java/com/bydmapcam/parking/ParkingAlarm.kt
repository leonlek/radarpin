package com.bydmapcam.parking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Tonight's "move the car" reminder.
 *
 * Deliberately an **inexact** alarm: `setAndAllowWhileIdle` still fires through doze, and asking
 * for an exact one would mean the SCHEDULE_EXACT_ALARM permission on Android 12+ for a message
 * whose whole point is "sometime before midnight". A few minutes either way changes nothing.
 *
 * There is only ever one, keyed by a fixed request code, so scheduling again replaces the last one
 * and driving off cancels whatever was pending without having to remember what it was.
 */
object ParkingAlarm {
    private const val ACTION = "com.bydmapcam.PARKING_REMINDER"
    const val EXTRA_NAME = "block_name"
    private const val REQUEST = 4101

    fun schedule(context: Context, blockName: String, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, blockName)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(intent(context, "")) }
    }

    private fun intent(context: Context, blockName: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, ParkingAlarmReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_NAME, blockName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** Fires late in the evening, with the car parked and the app almost certainly not on screen. */
class ParkingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(ParkingAlarm.EXTRA_NAME).orEmpty()
        ParkingNotifier.moveTonight(context, name.ifBlank { "บล็อกที่จอดอยู่" })
    }
}
