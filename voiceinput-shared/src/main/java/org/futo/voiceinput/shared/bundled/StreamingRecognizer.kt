package org.futo.voiceinput.shared.bundled

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.AudioRecognizerSettings
import org.futo.voiceinput.shared.R
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Bundled fallback engine: a streaming Kroko zipformer transducer running
 * in-process via sherpa-onnx. Transcribes WHILE the user speaks (partial
 * results stream live) and auto-stops on silence using the model's built-in
 * endpoint detection. Runs entirely in this process — no network use, ever.
 *
 * One model per language (en, de) is bundled; [setLanguage] picks which one
 * is loaded at the next dictation.
 */
class StreamingRecognizer(
    private val context: Context,
    private val lifecycleScope: CoroutineScope,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var language: Language = settings.language

    private var sessionJob: Job? = null

    @Volatile private var running = false

    @Volatile private var finishRequested = false

    private val useVADAutoStop get() = settings.recordingConfiguration.useVADAutoStop

    companion object {
        // Loaded models survive across dictations — reloading a 70 MB encoder
        // from assets on every session makes the mic appear dead for seconds.
        private val loadedRecognizers = HashMap<String, OnlineRecognizer>()
        private val loadLock = Any()

        fun loadedModelFor(language: Language): OnlineRecognizer? =
            synchronized(loadLock) { loadedRecognizers[languageTag(language)] }

        fun languageTag(language: Language): String = if (language == Language.German) "de" else "en"
    }

    fun setLanguage(language: Language) {
        this.language = language
    }

    fun reset() {}

    fun openPermissionSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun start() {
        mainHandler.post {
            println("StreamingRecognizer: start (${languageTag(language)}, cached=${loadedModelFor(language) != null})")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                listener.needPermission { granted -> if (granted) start() }
                return@post
            }
            sessionJob = lifecycleScope.launch(Dispatchers.IO) {
                runSession()
            }
        }
    }

    /** User tapped the overlay: stop capture and deliver the final text. */
    fun finish() {
        mainHandler.post {
            if (running) finishRequested = true
        }
    }

    /** Abandon the session entirely; no result is delivered. */
    fun cancel() {
        mainHandler.post {
            running = false
            finishRequested = false
        }
    }

    private suspend fun runSession() {
        var recorder: AudioRecord? = null
        var stream: OnlineStream? = null
        var pendingBuffer = ArrayList<FloatArray>()

        val deliver: (String) -> Unit = { text ->
            running = false
            mainHandler.post {
                if (text.isEmpty()) listener.cancelled() else listener.finished(text)
            }
        }

        try {
            val dir = languageTag(language)
            val recorderInstance = createRecorder()
            recorder = recorderInstance
            recorderInstance.startRecording()
            running = true
            mainHandler.post {
                listener.recordingStarted(noDevice)
            }
            println("StreamingRecognizer: mic live, language=$dir")

            // Prepare the recognizer while the mic is already running; audio
            // captured meanwhile is buffered and fed once decoding starts.
            val recognizerInstance = synchronized(loadLock) {
                loadedRecognizers[dir] ?: OnlineRecognizer(
                    context.assets,
                    OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = "streaming/$dir-encoder.onnx",
                                decoder = "streaming/$dir-decoder.onnx",
                                joiner = "streaming/$dir-joiner.onnx",
                            ),
                            tokens = "streaming/$dir-tokens.txt",
                            numThreads = 2,
                        ),
                        endpointConfig = EndpointConfig(
                            rule1 = EndpointRule(false, 20.0f, 20.0f),
                            rule2 = EndpointRule(false, 2.4f, 2.4f),
                            rule3 = EndpointRule(false, 20.0f, 2.4f),
                        ),
                        enableEndpoint = true,
                    )
                ).also {
                    println("StreamingRecognizer: model loaded for $dir")
                    loadedRecognizers[dir] = it
                }
            }
            val streamInstance = recognizerInstance.createStream()
            stream = streamInstance

            for (buffered in pendingBuffer) {
                streamInstance.acceptWaveform(buffered, 16000)
            }
            pendingBuffer = ArrayList()

            mainHandler.post {
                listener.updateMagnitude(0f, MagnitudeState.NOT_TALKED_YET)
            }

            val chunk = ShortArray(1600) // 100 ms at 16 kHz
            var saidSomething = false

            loop@ while (running && coroutineContext.isActive) {
                val n = recorderInstance.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break

                val floats = FloatArray(n) { chunk[it] / Short.MAX_VALUE.toFloat() }
                var sumSquares = 0.0
                for (f in floats) sumSquares += (f.toDouble() * f.toDouble())
                val rms = sqrt(sumSquares / floats.size).toFloat()

                streamInstance.acceptWaveform(floats, 16000)
                while (recognizerInstance.isReady(streamInstance)) {
                    recognizerInstance.decode(streamInstance)
                }

                val text = recognizerInstance.getResult(streamInstance).text.trim()
                val magnitude = (1.0f - 0.1f.pow(24.0f * rms))
                val state = if (text.isNotEmpty() || rms > 0.01f) MagnitudeState.TALKING
                else MagnitudeState.NOT_TALKED_YET
                mainHandler.post {
                    listener.updateMagnitude(magnitude, state)
                    if (text.isNotEmpty()) listener.partialResult(text)
                }
                if (text.isNotEmpty()) saidSomething = true

                if (recognizerInstance.isEndpoint(streamInstance)) {
                    if (useVADAutoStop) {
                        if (saidSomething) {
                            println("StreamingRecognizer: endpoint reached, delivering")
                            break@loop
                        }
                        recognizerInstance.reset(streamInstance)
                    } else {
                        recognizerInstance.reset(streamInstance)
                    }
                }
            }

            // Flush the decoder tail and deliver the final text.
            println("StreamingRecognizer: session ending (running=$running), delivering final text")
            streamInstance.inputFinished()
            while (recognizerInstance.isReady(streamInstance)) {
                recognizerInstance.decode(streamInstance)
            }
            val finalText = recognizerInstance.getResult(streamInstance).text.trim()
            withContext(Dispatchers.Main) { deliver(finalText) }
        } catch (e: Exception) {
            println("StreamingRecognizer: failed: $e")
            if (running) {
                mainHandler.post {
                    listener.recognitionFailed(
                        context.getString(R.string.recognition_error_generic) + " (${e.message})"
                    )
                }
            }
        } finally {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            try {
                stream?.release()
            } catch (_: Exception) {
            }
        }
    }

    private val noDevice = MicrophoneDeviceState(
        bluetoothAvailable = false,
        bluetoothActive = false,
        setBluetooth = { },
        deviceName = "",
        bluetoothPreferredByUser = false
    )

    private fun createRecorder(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
        }
        return recorder
    }
}
