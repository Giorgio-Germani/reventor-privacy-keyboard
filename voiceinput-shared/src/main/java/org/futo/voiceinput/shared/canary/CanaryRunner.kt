package org.futo.voiceinput.shared.canary

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.ggml.BailLanguageException
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.inferenceContext
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelBuiltInAsset
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
private const val LID_MODEL_ASSET = "ggml-tiny-q8_0.bin"
private const val LID_SAMPLE_COUNT = 16000 * 5

fun canaryApplies(languages: Set<Language>): Boolean =
    languages.isNotEmpty() && languages.all { it in CanaryLanguages }

/**
 * Speech recognition engine using NVIDIA Canary 180M Flash (via sherpa-onnx).
 * Canary does not perform language identification itself, so the first few
 * seconds of audio are run through a small multilingual whisper model to
 * detect the language before transcription.
 */
class CanaryRunner(private val context: Context) {
    private val lidLoader = ModelBuiltInAsset(name = 0, ggmlFile = LID_MODEL_ASSET)
    private var lidModel: org.futo.voiceinput.shared.ggml.WhisperGGML? = null
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLang: String? = null

    suspend fun preload() = withContext(inferenceContext) {
        obtainLidModel()
        // Warm up the recognizer itself so a broken model surfaces here
        // (and triggers the whisper fallback) instead of mid-dictation
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

    private suspend fun obtainLidModel(): org.futo.voiceinput.shared.ggml.WhisperGGML {
        lidModel?.let { return it }
        return lidLoader.loadGGML(context).also { lidModel = it }
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

    private suspend fun detectLanguage(samples: FloatArray, candidateLanguages: Set<Language>): String {
        val whisperStrings = candidateLanguages.map { it.toWhisperString() }.toTypedArray()
        val lidSamples = samples.copyOf(minOf(samples.size, LID_SAMPLE_COUNT))

        val lid = obtainLidModel()
        try {
            // All candidate languages are passed as bail languages, so inference
            // stops as soon as whisper has decided on a language and reports it
            // via BailLanguageException.
            lid.infer(
                samples = lidSamples,
                prompt = "",
                languages = whisperStrings,
                bailLanguages = whisperStrings,
                decodingMode = DecodingMode.Greedy,
                suppressNonSpeechTokens = false,
                partialResultCallback = { },
            )
        } catch (e: BailLanguageException) {
            return e.language
        }
        // Should not happen - every candidate language is a bail language
        throw IllegalStateException("Language detection did not determine a language")
    }

    @Throws(org.futo.voiceinput.shared.ggml.InferenceCancelledException::class)
    suspend fun run(
        samples: FloatArray,
        languages: Set<Language>,
        callback: ModelInferenceCallback,
    ): String = withContext(inferenceContext) {
        callback.updateStatus(InferenceState.Encoding)

        // Always detect the language over all supported languages, even if a
        // single language is configured: the user should never have to announce
        // which language they speak.
        val srcLang = detectLanguage(samples, CanaryLanguages)

        callback.languageDetected(
            languages.firstOrNull { it.toWhisperString() == srcLang } ?: Language.English
        )
        Log.d("CanaryRunner", "Detected language: $srcLang")

        try {
            val recognizer = obtainRecognizer(srcLang)
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
        lidModel?.close()
        lidModel = null
    }
}
