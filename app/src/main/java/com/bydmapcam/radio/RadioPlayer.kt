package com.bydmapcam.radio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streams FM Green Wave 106.5 (Atime Media) straight off the internet via a single
 * process-wide [MediaPlayer]. The player is not tied to the Activity, so audio keeps
 * playing while the app is backgrounded — the running LocationService keeps the
 * process alive. HLS (.m3u8) is decoded natively by MediaPlayer, no ExoPlayer needed.
 */
object RadioPlayer {
    // Audio-only HLS pulled from the atime.live web player (CORS-open, live).
    private const val STREAM_URL = "https://atimehd.smartclick.co.th/greenwave/hls/greenwave.m3u8"

    enum class State { STOPPED, BUFFERING, PLAYING, ERROR }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    /** Play if stopped/errored, otherwise stop. */
    fun toggle() {
        when (_state.value) {
            State.STOPPED, State.ERROR -> play()
            State.BUFFERING, State.PLAYING -> stop()
        }
    }

    private fun play() {
        release()
        _state.value = State.BUFFERING
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener { mp ->
                mp.start()
                _state.value = State.PLAYING
            }
            setOnErrorListener { _, _, _ ->
                release()
                _state.value = State.ERROR
                true
            }
            setDataSource(STREAM_URL)
            prepareAsync()
        }
    }

    fun stop() {
        release()
        _state.value = State.STOPPED
    }

    private fun release() {
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: IllegalStateException) {
                // player wasn't in a stoppable state — ignore, we're releasing anyway
            }
            mp.reset()
            mp.release()
        }
        player = null
    }
}
