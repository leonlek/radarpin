package com.bydmapcam.alert

import android.media.AudioManager
import android.media.ToneGenerator

/** Simple beep for proximity alerts. Fails silently if the audio device is busy. */
object Beeper {
    private var tone: ToneGenerator? = null

    private fun generator(): ToneGenerator? {
        if (tone == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        }
        return tone
    }

    fun beep() {
        runCatching { generator()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500) }
    }
}
