package org.futo.voiceinput.shared.canary

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.InferenceCancelledException
import org.futo.voiceinput.shared.inferenceContext
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.toWhisperString

// Languages supported by the bundled canary-180m-flash model
val CanaryLanguages: Set<Language> = setOf(
    Language.English,
    Language.German,
    Language.French,
    Language.Spanish,
)

private const val CANARY_ENCODER_ASSET = "encoder.int8.onnx"
private const val CANARY_DECODER_ASSET = "decoder.int8.onnx"
private const val CANARY_TOKENS_ASSET = "tokens.txt"

/**
 * Speech recognition engine using NVIDIA Canary 180M Flash (via sherpa-onnx).
 * Canary always transcribes into the language it is given; the user picks
 * which language they speak in and it is passed in with every run.
 */
class CanaryRunner(private val context: Context) {
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLang: String? = null

    suspend fun preload() = withContext(inferenceContext) {
        // Warm up the recognizer so a broken model surfaces here instead of
        // mid-dictation. A differing chosen language simply reloads it.
        obtainRecognizer(CanaryLanguages.first().toWhisperString())
    }

    /**
     * Drops the loaded ONNX recognizer so the next run reloads it from the
     * bundled assets. Used for self-healing after inference errors.
     */
    suspend fun invalidate() = withContext(inferenceContext) {
        try {
            recognizer?.release()
        } catch(_: Exception) {}
        recognizer = null
        recognizerLang = null
    }

    private fun obtainRecognizer(srcLang: String): OfflineRecognizer {
        recognizer?.let { existing ->
            if (recognizerLang == srcLang) return existing
            existing.release()
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(),
            modelConfig = OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = CANARY_ENCODER_ASSET,
                    decoder = CANARY_DECODER_ASSET,
                    srcLang = srcLang,
                    tgtLang = srcLang,
                    usePnc = true,
                ),
                tokens = CANARY_TOKENS_ASSET,
                debug = false,
            ),
        )
        return OfflineRecognizer(context.assets, config).also {
            recognizer = it
            recognizerLang = srcLang
        }
    }

    @Throws(InferenceCancelledException::class)
    suspend fun run(
        samples: FloatArray,
        language: Language,
        callback: ModelInferenceCallback,
    ): String = withContext(inferenceContext) {
        callback.updateStatus(InferenceState.Encoding)
        Log.d("CanaryRunner", "Transcribing as ${language.toWhisperString()}")

        try {
            val recognizer = obtainRecognizer(language.toWhisperString())
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()

            result.text.trim()
        } catch(e: Exception) {
            // The recognizer may be in a broken state; drop it so the next
            // dictation starts with a freshly loaded model
            invalidate()
            throw e
        }
    }

    suspend fun close() = withContext(inferenceContext) {
        recognizer?.release()
        recognizer = null
        recognizerLang = null
    }
}
