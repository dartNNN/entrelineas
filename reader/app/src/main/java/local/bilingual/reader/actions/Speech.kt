package local.bilingual.reader.actions

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Android's text-to-speech (the engine chosen in the phone's settings, usually Google) for the "Озвучить" action. */
/** Releases the speech engine connection; called when the reader's state is cleared. */
fun releaseSpeech() = Speech.release()

internal object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Locale>? = null

    fun release() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }

    fun say(context: Context, text: String, locale: Locale) {
        val engine = tts
        if (engine == null) {
            pending = text to locale
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) toast(context, "Синтез речи недоступен") else pending?.let { speak(context, it.first, it.second) }
                pending = null
            }
            return
        }
        if (ready) speak(context, text, locale) else pending = text to locale
    }

    private fun speak(context: Context, text: String, locale: Locale) {
        val engine = tts ?: return
        val available = engine.setLanguage(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast(context, "Нет голоса для языка «${locale.displayLanguage}»: установите его в настройках синтеза речи")
            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "entrelineas")
    }
}
