package org.futo.inputmethod.latin.uix

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.futo.voiceinput.shared.canary.CanaryLanguages
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.types.toWhisperString

// The language the Canary recognizer will transcribe with. It is mirrored
// here so the spacebar can display it synchronously (MainKeyboardView draws
// from this during onDraw, where the DataStore cannot be read). Use set()/-
// cycle() to change it; they persist through the VOICE_LANGUAGE setting and
// LatinIME invalidates the keyboard when that changes.
object VoiceLanguageState {
    @Volatile
    var current: Language = Language.English
        private set

    fun displayName(language: Language = current): String = when (language) {
        Language.English -> "English"
        Language.German -> "Deutsch"
        Language.Spanish -> "Español"
        Language.French -> "Français"
    }

    @JvmStatic
    fun currentName(): String = displayName()

    fun refresh(context: Context) {
        current = getLanguageFromWhisperString(context.getSettingBlocking(VOICE_LANGUAGE))
            ?: Language.English
    }

    fun set(context: Context, language: Language) {
        current = language
        CoroutineScope(Dispatchers.Default).launch {
            context.setSetting(VOICE_LANGUAGE, language.toWhisperString())
        }
    }

    fun cycle(context: Context): Language {
        val next = CanaryLanguages.elementAt(
            (CanaryLanguages.indexOf(current) + 1) % CanaryLanguages.size
        )
        set(context, next)
        return next
    }
}
