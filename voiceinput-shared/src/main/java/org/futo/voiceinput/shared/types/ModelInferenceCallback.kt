package org.futo.voiceinput.shared.types

enum class InferenceState {
    Encoding
}

interface ModelInferenceCallback {
    fun updateStatus(state: InferenceState)
    fun partialResult(string: String)
}
