package com.bydmapcam.alert

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thai text-to-speech for voice alerts. No-op until initialized and only if a voice is available. */
object Speaker {
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.language = Locale("th", "TH") }
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "byd-alert") }
    }
}
