package com.bydmapcam.parking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bydmapcam.MainActivity
import com.bydmapcam.R

/**
 * The two things the app ever says out loud about parking. Both are notifications rather than the
 * alert banner: neither happens while driving — one lands as the driver is getting out, the other
 * late at night with the car empty — and a banner nobody is sitting in front of says nothing.
 */
object ParkingNotifier {
    private const val CHANNEL_ID = "parking"
    private const val ID_WRONG_SIDE = 2001
    private const val ID_MOVE_TONIGHT = 2002

    /** Parked on a kerb that today's rule doesn't allow. */
    fun wrongSide(context: Context, blockName: String, state: ParkingState) {
        post(
            context,
            ID_WRONG_SIDE,
            title = state.label,
            text = "$blockName — จอดฝั่งนี้วันนี้เสี่ยงโดนใบสั่ง ลองย้ายไปอีกฝั่ง"
        )
    }

    /** Parked legally, but the kerbs swap at midnight. */
    fun moveTonight(context: Context, blockName: String) {
        post(
            context,
            ID_MOVE_TONIGHT,
            title = "เที่ยงคืนนี้ฝั่งจอดสลับ",
            text = "$blockName — ย้ายรถไปอีกฝั่งก่อนเที่ยงคืน"
        )
    }

    /** Both messages are about a car that is still parked; driving off makes them both wrong. */
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).apply {
            cancel(ID_WRONG_SIDE)
            cancel(ID_MOVE_TONIGHT)
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        // Posting without POST_NOTIFICATIONS throws on 13+; a missing reminder must not take the
        // service down with it.
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ที่จอดรถ", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
