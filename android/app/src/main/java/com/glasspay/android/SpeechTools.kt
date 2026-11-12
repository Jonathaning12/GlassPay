package com.glasspay.android

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceRecognizer(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onEnd: () -> Unit,
    private val onPartial: () -> Unit,
    private val reportError: (Int, String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resultDelivered = false
    private var released = false
    private val recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    private var recognizer: SpeechRecognizer? = null

    init {
        recognizer = createRecognizer(appContext)
    }

    private fun createRecognizer(context: Context): SpeechRecognizer? {
        if (!recognitionAvailable) return null
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) {
                    reportError(SpeechRecognizer.ERROR_NO_MATCH, L("No escuché una frase."))
                } else {
                    resultDelivered = true
                    onResult(text)
                }
            }

            override fun onError(error: Int) {
                if (released || resultDelivered) return
                reportError(error, voiceErrorMessage(error))
            }

            override fun onReadyForSpeech(params: Bundle?) = onReady()
            override fun onBeginningOfSpeech() = onReady()
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onEnd()
            override fun onPartialResults(partialResults: Bundle?) = onPartial()
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        return created
    }

    fun isAvailable(): Boolean = recognitionAvailable

    fun start() {
        mainHandler.post {
            released = false
            val speechRecognizer = recognizer ?: run {
                recognizer = createRecognizer(appContext)
                recognizer
            } ?: run {
                reportError(
                    SpeechRecognizer.ERROR_CLIENT,
                    L("El reconocimiento de voz no está disponible. Usa texto."),
                )
                return@post
            }
            try {
                resultDelivered = false
                speechRecognizer.cancel()
                speechRecognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.speechTag)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage.speechTag)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    },
                )
            } catch (_: SecurityException) {
                reportError(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    L("Permiso de micrófono denegado. Usa la entrada por texto."),
                )
            } catch (_: RuntimeException) {
                reportError(
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    L("El servicio de voz está ocupado o no pudo iniciar. Intenta de nuevo."),
                )
            }
        }
    }

    fun cancel() {
        mainHandler.post { recognizer?.cancel() }
    }

    fun release() {
        mainHandler.post {
            released = true
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    fun destroy() {
        released = true
        recognizer?.destroy()
        recognizer = null
    }
}

fun voiceErrorMessage(error: Int): String {
    return when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            L("Permiso de micrófono denegado. Usa la entrada por texto.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            L("El servicio de voz está ocupado. Intenta de nuevo.")
        SpeechRecognizer.ERROR_NO_MATCH ->
            L("No entendí la frase. Intenta de nuevo.")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            L("No detecté voz. Intenta de nuevo.")
        else -> L("No pudimos usar el reconocimiento de voz. Usa la entrada por texto.")
    }
}

/**
 * Ruta de audio compartida por el dictado y la voz de la app: los lentes (Bluetooth SCO) si están
 * enlazados y el usuario no eligió el teléfono. Se libera antes de volver a escanear, porque el video
 * de los lentes necesita el ancho de banda de Bluetooth.
 */
class AudioRoute(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val preferences = context.applicationContext
        .getSharedPreferences("glasspay.audio", Context.MODE_PRIVATE)
    private var active = false

    var prefersGlasses: Boolean
        get() = preferences.getBoolean(PREFERS_GLASSES_KEY, true)
        set(value) = preferences.edit().putBoolean(PREFERS_GLASSES_KEY, value).apply()

    /** Hay lentes u otro auricular Bluetooth que se puede usar para dictar. */
    fun hasBluetoothDevice(): Boolean = bluetoothDevice() != null

    fun activate() {
        val device = if (prefersGlasses) bluetoothDevice() else null
        if (device == null) {
            release()
            return
        }
        // El modo de comunicación da prioridad a esta app sobre la ruta; la plataforma elige el micrófono a juego.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        active = audioManager.setCommunicationDevice(device)
        if (!active) audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun release() {
        if (!active) return
        active = false
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    val usesBluetooth: Boolean
        get() = active && audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /** Por dónde se escucha ahora mismo; no incluye datos del usuario. */
    val inputLabel: String
        get() = if (usesBluetooth) {
            audioManager.communicationDevice?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
        } else {
            L("el teléfono")
        }

    private fun bluetoothDevice(): AudioDeviceInfo? =
        audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private companion object {
        const val PREFERS_GLASSES_KEY = "prefersGlassesAudio"
    }
}

class SpeechOutput(
    context: Context,
    private val audioRoute: AudioRoute? = null,
) {
    private var ready = false
    private val speech: TextToSpeech
    private val mainHandler = Handler(Looper.getMainLooper())
    // Cada speak/stop invalida los avisos de fin pendientes: el flujo que los pidió ya cambió.
    @Volatile private var generation = 0

    init {
        speech = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) applyLanguage()
        }
    }

    // Voz en el idioma de la app; se ajusta al hablar por si el usuario lo cambió en Perfil.
    private var spokenLanguage: AppLanguage? = null

    private fun applyLanguage() {
        val language = currentLanguage
        if (spokenLanguage == language) return
        runCatching { speech.language = language.locale }
        spokenLanguage = language
    }

    fun speak(text: String, utteranceId: String, onDone: () -> Unit = {}) {
        val current = ++generation
        if (ready) applyLanguage()
        audioRoute?.activate()
        val bluetooth = audioRoute?.usesBluetooth == true
        // Por Bluetooth SCO solo suena el audio de comunicación; en el teléfono se conserva el de siempre
        // (multimedia, el valor por defecto de TextToSpeech), que sale por la bocina.
        speech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (bluetooth) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val finish = {
            if (bluetooth) {
                // El audio por Bluetooth termina de sonar después del aviso del sintetizador. Sin esta
                // pausa, el micrófono de los lentes capta el final de la frase (anti-eco).
                mainHandler.postDelayed({ if (generation == current) onDone() }, BLUETOOTH_ECHO_DELAY_MILLIS)
            } else {
                onDone()
            }
        }
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (id == utteranceId) finish()
            }
            override fun onError(id: String?) {
                if (id == utteranceId) finish()
            }
        })
        if (ready) {
            speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            onDone()
        }
    }

    fun stop() {
        generation++
        speech.stop()
    }

    fun shutdown() {
        generation++
        speech.stop()
        speech.shutdown()
    }

    private companion object {
        const val BLUETOOTH_ECHO_DELAY_MILLIS = 700L
    }
}

fun isConfirmation(text: String, language: AppLanguage = currentLanguage): Boolean =
    normalizeCommandText(text) in VoiceCommandWords.forLanguage(language).confirm

fun isCancellation(text: String, language: AppLanguage = currentLanguage): Boolean =
    normalizeCommandText(text) in VoiceCommandWords.forLanguage(language).cancel
