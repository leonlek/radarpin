package com.bydmapcam.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.bydmapcam.radio.RadioPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads and drives whatever else is playing audio on the head unit (Spotify, YouTube Music, the
 * stock player, a phone over Bluetooth…).
 *
 * Deliberately two-tier:
 *  - **transport always works** — media key events go to whichever app owns the media session, no
 *    permission needed, exactly like the steering-wheel buttons;
 *  - **the track title needs "notification access"** ([hasAccess]) because that is the only way
 *    Android lets one app read another app's [MediaController]. Without it the bar still appears
 *    while media is genuinely playing — just as buttons, with no title.
 *
 * Note this can't reach audio that never becomes an Android media session — most importantly
 * CarPlay, which is a separate projection channel owned by the iPhone.
 */
object MediaLink {
    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean
    ) {
        /** One line for the bar, or null when we can't read the track — then it's buttons only. */
        val label: String?
            get() = when {
                title.isNullOrBlank() -> null
                artist.isNullOrBlank() -> title
                else -> "$title — $artist"
            }
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            bind(null)
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickSession(list.orEmpty()) }

    /** true once the user has ticked this app in Settings → notification access. */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Opens the notification-access screen. Car head units are heavily cut-down Android builds and
     * often don't ship it at all — hence trying the per-app screen, then the list, then Settings
     * itself, and saying so plainly rather than letting the system throw "not supported" at the user.
     *
     * @return false when the device has nowhere to send them.
     */
    fun openAccessSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, MediaNotificationListener::class.java)
                            .flattenToString()
                    )
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("DEPRECATION")
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** Start watching sessions (safe to call repeatedly — e.g. every time the app resumes). */
    fun start(context: Context) {
        if (!hasAccess(context)) return
        val mgr = manager ?: context.getSystemService(MediaSessionManager::class.java)?.also {
            manager = it
        } ?: return
        val component = ComponentName(context, MediaNotificationListener::class.java)
        runCatching {
            mgr.removeOnActiveSessionsChangedListener(sessionsListener)
            mgr.addOnActiveSessionsChangedListener(sessionsListener, component)
            pickSession(mgr.getActiveSessions(component))
        }
    }

    /**
     * Without notification access there's no session to read, so fall back to asking the audio
     * system whether anything is *actually* rendering media right now. Deliberately not
     * [AudioManager.isMusicActive]: a head unit tends to hold a music-stream player open forever,
     * which made the bar appear the moment the app launched. Checking the active playback
     * configurations for a real USAGE_MEDIA player is the closest thing to "sound is coming out".
     */
    fun refreshWithoutAccess(context: Context) {
        if (controller != null) return
        // Our own radio also counts as playback — don't offer to "control" ourselves.
        val ours = RadioPlayer.state.value != RadioPlayer.State.STOPPED &&
            RadioPlayer.state.value != RadioPlayer.State.ERROR
        val audio = context.getSystemService(AudioManager::class.java)
        val playing = when {
            ours || audio == null -> false
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                audio.activePlaybackConfigurations.any {
                    it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
                }
            else -> audio.isMusicActive
        }
        _nowPlaying.value = if (playing) NowPlaying(null, null, playing = true) else null
    }

    fun stop() {
        manager?.runCatching { removeOnActiveSessionsChangedListener(sessionsListener) }
        bind(null)
    }

    /** Prefer whatever is actually playing; otherwise the most recent session. */
    private fun pickSession(sessions: List<MediaController>) {
        bind(sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull())
    }

    private fun bind(next: MediaController?) {
        if (controller?.sessionToken == next?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback)
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _nowPlaying.value = null
            return
        }
        // A paused app keeps its session alive for as long as it likes, so "a session exists" would
        // leave the bar sitting there over the map long after the music stopped. Sound coming out of
        // the speakers is the only thing that earns the space.
        val playing = c.playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
        }
        if (!playing) {
            _nowPlaying.value = null
            return
        }
        val md = c.metadata
        _nowPlaying.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            playing = true
        )
    }

    fun playPause(context: Context) {
        val c = controller ?: return dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    /** Silence other players — used when our own radio takes over the speakers. */
    fun pause(context: Context) {
        val c = controller
        if (c != null) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        } else if (context.getSystemService(AudioManager::class.java)?.isMusicActive == true) {
            dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    fun next(context: Context) {
        controller?.transportControls?.skipToNext()
            ?: dispatchKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(context: Context) {
        controller?.transportControls?.skipToPrevious()
            ?: dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** The no-permission path: same event the steering-wheel buttons send. */
    private fun dispatchKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }
}
