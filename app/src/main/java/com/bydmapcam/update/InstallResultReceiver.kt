package com.bydmapcam.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * What the package installer has to say about our session.
 *
 * The one that matters is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system hands back an
 * intent that opens its own "install this?" dialog, and until somebody starts it nothing happens at
 * all — the download would just sit there looking finished.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Updates.failed("ระบบไม่เปิดหน้ายืนยันการติดตั้ง")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Updates.failed("เปิดหน้ายืนยันการติดตั้งไม่ได้") }
            }
            // Nothing to report: the app is about to be replaced under our feet.
            PackageInstaller.STATUS_SUCCESS -> Updates.clear()
            else -> Updates.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
