package org.futo.voiceinput.shared.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import org.futo.voiceinput.shared.AudioRecognizerSettings
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.toLanguageTag
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

/**
 * Dictation using Google's on-device speech engine.
 *
 * Strictly offline: only SpeechRecognizer.createOnDeviceSpeechRecognizer() is
 * ever used, which the platform guarantees processes all recognition on the
 * device with no network access. If that engine is unavailable (API < 31, or
 * the device has no on-device recognition, e.g. de-Googled ROMs), start()
 * reports [AudioRecognizerListener.recognitionFailed] rather than falling
 * back to any network-capable recognizer.
 *
 * SpeechRecognizer performs its own mic capture and end-of-speech detection
 * and exposes no raw audio, so VAD-based auto-stop is replaced by the
 * engine's endpointer. Loudness for the bubble animation comes from
 * [RecognitionListener.onRmsChanged].
 *
 * The public surface mirrors the old AudioRecognizer so RecognizerView can
 * use either without changes.
 */
class SystemSpeechRecognizer(
    private val context: Context,
    @Suppress("unused") lifecycleScope: kotlinx.coroutines.CoroutineScope,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings,
) {
    companion object {
        /** Whether offline recognition is possible on this device at all. */
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Intent that opens Google's voice/offline-language download UI. */
        fun languageInstallerIntent(context: Context): Intent? =
            RecognizerIntent.getVoiceDetailsIntent(context)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language: Language = settings.language
    private var running = false

    @Volatile private var gotReady = false

    private var failoverDone = false

    /**
     * Google's service can deliver several errors for one failed session
     * (e.g. "pack missing" followed ~5 s later by a generic CLIENT error).
     * Only the first error of a session is reported — it's the root cause.
     */
    private var reportedFatalError = false

    /** Changes the language the next dictation is transcribed as. */
    fun setLanguage(language: Language) {
        this.language = language
    }

    fun reset() {
        // No persistent state between sessions; errors are cleared on start.
    }

    fun start() {
        mainHandler.post {
            println("SystemSpeech: start requested, supported=${isSupported(context)} (API ${Build.VERSION.SDK_INT})")
            if (!isSupported(context)) {
                listener.recognitionFailed(
                    context.getString(org.futo.voiceinput.shared.R.string.offline_recognition_unavailable)
                )
                return@post
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                listener.needPermission { granted ->
                    if (granted) start()
                }
                return@post
            }
            startListening()
        }
    }

    /** User tapped the overlay: stop capture and deliver the final result. */
    fun finish() {
        mainHandler.post {
            println("SystemSpeech: finish requested (tap)")
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
            // A broken service may simply ignore stopListening. If the
            // session is still running 1.5 s after the tap, hand over to
            // the bundled engine instead of leaving the user stuck.
            mainHandler.postDelayed({
                if (running) {
                    println("SystemSpeech: stop request ignored, engine unresponsive")
                    failover()
                }
            }, 1500)
        }
    }

    /** Abandon the session entirely; no result is delivered. */
    fun cancel() {
        mainHandler.post {
            running = false
            destroyRecognizer()
        }
    }

    /** Opens the system app settings so the user can grant mic access. */
    fun openPermissionSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * The bound service is not delivering (zombie session): kill it and
     * report failure so RecognizerView switches to the bundled engine.
     */
    private fun failover() {
        if (failoverDone) return
        failoverDone = true
        running = false
        destroyRecognizer()
        listener.recognitionFailed(
            context.getString(org.futo.voiceinput.shared.R.string.recognition_error_generic)
        )
    }

    private fun startListening() {
        destroyRecognizer()
        reportedFatalError = false
        failoverDone = false
        listener.loading()

        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(recognitionListener)

        val intent = recognitionIntent(language.toLanguageTag())

        // Ask the bound service what it can actually do for this language.
        // This is the authoritative view (the Google app's pack list can
        // disagree with the service that ends up doing the work).
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                sr.checkRecognitionSupport(intent, { it.run() },
                    object : android.speech.RecognitionSupportCallback {
                        override fun onSupportResult(support: android.speech.RecognitionSupport) {
                            println(
                                "SystemSpeech: support for ${intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}: " +
                                    "installed=${support.installedOnDeviceLanguages} " +
                                    "pending=${support.pendingOnDeviceLanguages} " +
                                    "online=${support.onlineLanguages}"
                            )
                            // If our first choice isn't installed but another
                            // variant of the same language is, use that.
                            if (Build.VERSION.SDK_INT >= 33 &&
                                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
                                    ?.let { it !in support.installedOnDeviceLanguages } == true
                            ) {
                                val wanted = language.toLanguageTag().substringBefore('-')
                                val alternate = support.installedOnDeviceLanguages.firstOrNull {
                                    it.substringBefore('-') == wanted
                                }
                                if (alternate != null) {
                                    println("SystemSpeech: falling back to installed variant $alternate")
                                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, alternate)
                                }
                            }
                        }

                        override fun onError(error: Int) {
                            println("SystemSpeech: support check failed code=$error")
                        }
                    }
                )
            } catch (e: Exception) {
                println("SystemSpeech: support check threw ${e.message}")
            }
        }

        running = true
        gotReady = false
        println("SystemSpeech: startListening ${language.toLanguageTag()}")
        sr.startListening(intent)

        // If the service never even becomes ready, nothing else will happen
        // either — fail over to the bundled engine.
        mainHandler.postDelayed({
            if (running && !gotReady) {
                println("SystemSpeech: watchdog — not ready after 4 s")
                failover()
            }
        }, 4000)
    }

    private fun recognitionIntent(tag: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Belt and braces: the on-device factory already guarantees this.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun destroyRecognizer() {
        recognizer?.let { sr ->
            try {
                sr.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private val noDevice = MicrophoneDeviceState(
        bluetoothAvailable = false,
        bluetoothActive = false,
        setBluetooth = { },
        deviceName = "",
        bluetoothPreferredByUser = false
    )

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            gotReady = true
            listener.recordingStarted(noDevice)
        }

        override fun onBeginningOfSpeech() {
            listener.updateMagnitude(0.15f, MagnitudeState.TALKING)
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Roughly -2..10 dB in practice; squeeze into 0..1 for the bubble.
            val magnitude = ((rmsdB + 2f) / 10f).coerceIn(0.05f, 1f)
            listener.updateMagnitude(magnitude, MagnitudeState.TALKING)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listener.processing()
        }

        override fun onError(error: Int) {
            running = false
            println("SystemSpeech: onError code=$error")
            // The session is dead either way; free it so the next start is clean.
            destroyRecognizer()
            if (reportedFatalError) return
            reportedFatalError = true
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    listener.needPermission { granted -> if (granted) start() }

                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> listener.cancelled()

                else -> listener.recognitionFailed(errorMessage(error))
            }
        }

        override fun onResults(results: Bundle?) {
            running = false
            gotReady = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                listener.cancelled()
            } else {
                listener.finished(text)
            }
            destroyRecognizer()
            reportedFatalError = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) listener.partialResult(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorMessage(error: Int): String = context.getString(
        when (error) {
            // Public constant; Google's service also reports its internal
            // code 13 for the same condition on some Android versions.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, 13 ->
                org.futo.voiceinput.shared.R.string.recognition_error_language_pack

            else -> org.futo.voiceinput.shared.R.string.recognition_error_generic
        }
    ) + " (code $error)"
}
