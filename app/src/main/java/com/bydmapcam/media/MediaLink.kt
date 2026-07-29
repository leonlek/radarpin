package com.bydmapcam.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
 *    Android lets one app read another app's [MediaController]. Without it we can still tell that
 *    *something* is playing ([AudioManager.isMusicActive]) and say so.
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
        /** One line for the bar: "Title — Artist", or a generic label when we can't read metadata. */
        val label: String
            get() = when {
                title.isNullOrBlank() -> "เพลงจากแอปอื่น"
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

    fun stop() {
        manager?.runCatching { removeOnActiveSessionsChangedListener(sessionsListener) }
        bind(null)
    }

    /**
     * Without notification access there is no session to read, so fall back to "is the music stream
     * busy" — enough to decide whether the bar is worth showing at all. Cheap; poll it, don't spam.
     */
    fun refreshWithoutAccess(context: Context) {
        if (controller != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        // Our own radio also lights up isMusicActive — don't offer to "control" ourselves.
        val ours = RadioPlayer.state.value != RadioPlayer.State.STOPPED &&
            RadioPlayer.state.value != RadioPlayer.State.ERROR
        _nowPlaying.value =
            if (audio?.isMusicActive == true && !ours) NowPlaying(null, null, playing = true)
            else null
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
