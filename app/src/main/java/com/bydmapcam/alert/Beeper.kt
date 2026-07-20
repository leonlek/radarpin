package com.bydmapcam.alert

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Simple beep for proximity alerts. Fails silently if the audio device is busy. */
object Beeper {
    private var tone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun generator(): ToneGenerator? {
        if (tone == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        }
        return tone
    }

    /** Play [count] short beeps in quick succession — more beeps = more urgent (closer). */
    fun beep(count: Int = 1) {
        val g = generator() ?: return
        val n = count.coerceIn(1, 4)
        fun play(i: Int) {
            runCatching { g.startTone(ToneGenerator.TONE_PROP_BEEP2, 160) }
            if (i + 1 < n) handler.postDelayed({ play(i + 1) }, 230)
        }
        play(0)
    }
}
