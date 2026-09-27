package org.futo.voiceinput.shared.types

// The languages the bundled canary-180m-flash model supports; the user picks
// which one they speak and it is passed to the recognizer directly.
enum class Language {
    English,
    German,
    Spanish,
    French,
}


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
