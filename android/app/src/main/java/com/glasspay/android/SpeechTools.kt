package com.glasspay.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private val audioPermissionGranted =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    private var recognizer: SpeechRecognizer? = null

    init {
        if (BuildConfig.DEBUG) {
            Log.d(
                "GlassPayVoice",
                "recognitionAvailable=$recognitionAvailable " +
                    "recordAudioGranted=$audioPermissionGranted",
            )
        }
        recognizer = createRecognizer(appContext)
    }

    private fun createRecognizer(context: Context): SpeechRecognizer? {
        if (!recognitionAvailable) return null
        if (BuildConfig.DEBUG) Log.d("GlassPayVoice", "createSpeechRecognizer:before")
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        if (BuildConfig.DEBUG) Log.d("GlassPayVoice", "createSpeechRecognizer:after")
        created.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) {
                    reportError(SpeechRecognizer.ERROR_NO_MATCH, "No escuché una frase.")
                } else {
                    resultDelivered = true
                    onResult(text)
                }
            }

            override fun onError(error: Int) {
                if (released || resultDelivered) return
                if (BuildConfig.DEBUG) {
                    Log.d("GlassPayVoice", "recognitionErrorCode=$error")
                }
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
                    "El reconocimiento de voz no está disponible. Usa texto.",
                )
                return@post
            }
            try {
                resultDelivered = false
                if (BuildConfig.DEBUG) Log.d("GlassPayVoice", "cancel:before")
                speechRecognizer.cancel()
                if (BuildConfig.DEBUG) Log.d("GlassPayVoice", "cancel:after")
                if (BuildConfig.DEBUG) Log.d("GlassPayVoice", "startListening:before")
                speechRecognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    },
                )
                if (BuildConfig.DEBUG) Log.d("GlassPayVoice", "startListening:after")
            } catch (exception: SecurityException) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "GlassPayVoice",
                        "startListening exceptionClass=${exception.javaClass.name} " +
                            "message=${exception.message}",
                    )
                }
                reportError(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    "Permiso de micrófono denegado. Usa la entrada por texto.",
                )
            } catch (exception: RuntimeException) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "GlassPayVoice",
                        "startListening exceptionClass=${exception.javaClass.name} " +
                            "message=${exception.message}",
                    )
                }
                reportError(
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    "El servicio de voz está ocupado o no pudo iniciar. Intenta de nuevo.",
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
            "Permiso de micrófono denegado. Usa la entrada por texto."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "El servicio de voz está ocupado. Intenta de nuevo."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "No entendí la frase. Intenta de nuevo."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No detecté voz. Intenta de nuevo."
        else -> "No pudimos usar el reconocimiento de voz. Usa la entrada por texto."
    }
}

class SpeechOutput(
    context: Context,
) {
    private var ready = false
    private val speech: TextToSpeech

    init {
        speech = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) speech.language = Locale("es", "MX")
        }
    }

    fun speak(text: String, utteranceId: String, onDone: () -> Unit = {}) {
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (id == utteranceId) onDone()
            }
            override fun onError(id: String?) {
                if (id == utteranceId) onDone()
            }
        })
        if (ready) {
            speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            onDone()
        }
    }

    fun shutdown() {
        speech.stop()
        speech.shutdown()
    }
}

fun isConfirmation(text: String): Boolean {
    val normalized = normalizeCommandText(text)
    return normalized == "confirmar" || normalized == "si"
}

fun isCancellation(text: String): Boolean {
    val normalized = normalizeCommandText(text)
    return normalized == "no" || normalized == "cancelar"
}
