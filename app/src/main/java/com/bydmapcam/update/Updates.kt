package com.bydmapcam.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.bydmapcam.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updating a sideloaded app on a head unit with no store behind it.
 *
 * The version to compare against is a file we publish next to the camera dataset rather than the
 * GitHub release API: every build is uploaded over the *same* tag and asset name so the browser
 * link in the car never changes, which means the API can't tell one build from the next but a file
 * we write can. Keeping that file in step with the uploaded APK is part of releasing, not something
 * the app can check.
 *
 * The install itself is the system's to approve — a silent one needs privileges a sideloaded app
 * will never have — and on a locked-down head unit even the *permission* to ask may be unreachable.
 * So every path ends somewhere useful: install in place if the unit allows it, and if it doesn't,
 * hand the driver the same download page they installed from in the first place.
 */
object Updates {

    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/leonlek/radarpin/main/data/latest.json"

    /** Where to send the driver when we can't install it for them. */
    const val DOWNLOAD_PAGE = "https://leonlek.github.io/radarpin/"

    /** A day between automatic checks: releases are days apart and the car pays for the data. */
    private const val CHECK_EVERY_MS = 24 * 60 * 60_000L

    private const val ACTION_INSTALL_STATUS = "com.bydmapcam.INSTALL_STATUS"

    sealed interface State {
        /** Nothing to say — either up to date, or not looked yet. */
        data object Idle : State

        data class Available(val versionCode: Long, val notes: String, val url: String) : State

        data class Downloading(val percent: Int) : State

        /** Downloaded and handed to the system; the confirm dialog is the driver's to accept. */
        data object Installing : State

        /** Something went wrong far enough along that the driver should hear about it. */
        data class Failed(val message: String) : State
    }

    enum class Result { FOUND, UP_TO_DATE, FAILED, SKIPPED }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Waved away for this run; the next launch asks again. */
    @Volatile
    private var dismissedVersion = 0L

    /**
     * Ask what the newest build is. [force] is the driver pressing the button: it ignores both the
     * setting and the once-a-day throttle, and reports back rather than staying quiet.
     */
    suspend fun check(context: Context, force: Boolean = false): Result {
        if (!force) {
            if (!Settings.updateCheck(context)) return Result.SKIPPED
            val since = System.currentTimeMillis() - Settings.updateCheckedAt(context)
            if (since < CHECK_EVERY_MS) return Result.SKIPPED
        }
        val json = withContext(Dispatchers.IO) { fetch(MANIFEST_URL) } ?: return Result.FAILED
        Settings.recordUpdateCheck(context)
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return Result.FAILED
        val version = obj.optLong("versionCode")
        val url = obj.optString("url")
        if (version <= installedVersion(context) || url.isBlank()) return Result.UP_TO_DATE
        if (!force && version == dismissedVersion) return Result.SKIPPED
        _state.value = State.Available(version, obj.optString("notes"), url)
        return Result.FOUND
    }

    /** Fetch the APK and hand it to the package installer, reporting progress as it goes. */
    suspend fun download(context: Context, update: State.Available) {
        _state.value = State.Downloading(0)
        val file = withContext(Dispatchers.IO) { downloadApk(update.url, context.cacheDir) }
        if (file == null) {
            _state.value = State.Failed("โหลดไฟล์ไม่สำเร็จ")
            return
        }
        if (!canInstall(context)) {
            // The unit hasn't been told we may install; ask, and if even that page is missing —
            // which is exactly what this head unit does with the notification-access page — the
            // browser fallback in the card is all that's left.
            _state.value = State.Failed("ต้องอนุญาต \"ติดตั้งแอปที่ไม่รู้จัก\" ให้ RadarPin ก่อน")
            requestInstallPermission(context)
            return
        }
        _state.value = State.Installing
        val handed = withContext(Dispatchers.IO) { commit(context, file) }
        if (!handed) _state.value = State.Failed("ส่งไฟล์ให้ตัวติดตั้งไม่สำเร็จ")
    }

    /** Not now — quiet until the next launch, or until a newer build than this one appears. */
    fun dismiss() {
        (_state.value as? State.Available)?.let { dismissedVersion = it.versionCode }
        _state.value = State.Idle
    }

    fun clear() {
        _state.value = State.Idle
    }

    internal fun failed(message: String?) {
        _state.value = State.Failed(message?.takeIf { it.isNotBlank() } ?: "ติดตั้งไม่สำเร็จ")
    }

    /** Open the page the app was first installed from — the browser may install what we can't. */
    fun openDownloadPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun installedVersion(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // before Oreo it's one global switch, and sideloading is already on if we're here
        }

    private fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun downloadApk(url: String, dir: File): File? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        val total = conn.contentLength.toLong()
        val out = File(dir, "update.apk")
        conn.inputStream.use { input ->
            out.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = State.Downloading(percent)
                        }
                    }
                }
            }
        }
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun commit(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("radarpin", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable on purpose: the system fills this intent in with the status, and on 12+ an
            // immutable one is rejected outright.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                flags
            )
            session.commit(pending.intentSender)
        }
        true
    }.getOrDefault(false)
}
