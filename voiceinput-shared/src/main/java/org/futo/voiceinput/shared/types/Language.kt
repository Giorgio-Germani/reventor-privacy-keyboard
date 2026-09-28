package org.futo.voiceinput.shared.types

/**
 * Languages the user can pick for dictation. The user chooses explicitly —
 * there is no automatic language detection.
 *
 * The recognizer is Google's on-device engine (SpeechRecognizer on-device),
 * which itself supports many more languages than listed here; this set is
 * what the voice window chips and the spacebar language cycling offer, kept
 * intentionally small and matching the keyboard layouts in the app.
 */
enum class Language {
    English,
    German,
    Spanish,
    French,
}

/** Order used by the language chips and the cycle control. */
val SupportedLanguages: List<Language> = listOf(
    Language.English,
    Language.German,
    Language.French,
    Language.Spanish,
)

/** Canonical short tag persisted in the VOICE_LANGUAGE setting (and used to match keyboard layouts). */
fun Language.toWhisperString(): String {
    return when (this) {
        Language.English -> "en"
        Language.German -> "de"
        Language.Spanish -> "es"
        Language.French -> "fr"
    }
}


fun getLanguageFromWhisperString(str: String): Language? {
    return when (str) {
        "en" -> Language.English
        "de" -> Language.German
        "es" -> Language.Spanish
        "fr" -> Language.French
        else -> null
    }
}

/** BCP-47 tag passed to the system recognizer for this language. */
fun Language.toLanguageTag(): String {
    return when (this) {
        Language.English -> "en-US"
        Language.German -> "de-DE"
        Language.Spanish -> "es-ES"
        Language.French -> "fr-FR"
    }
}
