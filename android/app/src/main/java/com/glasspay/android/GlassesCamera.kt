package com.glasspay.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Cámara de los lentes (Meta Wearables DAT): el equivalente a CameraScanner cuando se cobra mirando el QR.
// Solo entrega tokens con prefijo "glasspay:". No guarda imágenes ni tokens; el video vive solo en memoria.

/** Inicialización única del SDK. Requiere el permiso BLUETOOTH_CONNECT; sin él, los lentes no se usan. */
object GlassesSupport {
    private val initialized = AtomicBoolean(false)

    val isReady: Boolean get() = initialized.get()

    fun hasBluetoothPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Devuelve si el SDK quedó listo. Una segunda llamada no hace nada. */
    fun initializeIfAllowed(context: Context): Boolean {
        if (initialized.get()) return true
        if (!hasBluetoothPermission(context)) return false
        Wearables.initialize(context.applicationContext)
            .onSuccess { initialized.set(true) }
            .onFailure { error, _ -> Log.i("GlassPay", "glasses initialize failure ${error.description}") }
        return initialized.get()
    }
}

/**
 * Registro, sesión y video de los lentes. El estado es de Compose para que las pantallas lo observen.
 * Quien la crea llama a [tearDown] al terminar: una sesión abierta mantiene la cámara de los lentes activa.
 */
class GlassesCamera {
    var registration by mutableStateOf(RegistrationState.UNAVAILABLE)
        private set
    var hasActiveDevice by mutableStateOf(false)
        private set
    var sessionState by mutableStateOf(DeviceSessionState.IDLE)
        private set
    var streamState by mutableStateOf(StreamState.STOPPED)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var showPermissionPrompt by mutableStateOf(false)

    val isRegistered: Boolean get() = registration == RegistrationState.REGISTERED
    val isPaused: Boolean get() = streamState == StreamState.PAUSED
    val hasSession: Boolean
        get() = when (sessionState) {
            DeviceSessionState.STARTING, DeviceSessionState.STARTED,
            DeviceSessionState.PAUSED, DeviceSessionState.STOPPING -> true
            DeviceSessionState.IDLE, DeviceSessionState.STOPPED -> false
        }

    var onToken: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Perezoso: crearlo llama al SDK, que puede no estar iniciado (sin permiso de Bluetooth).
    private val selector by lazy { AutoDeviceSelector() }
    private val reader = GlassesQrReader()
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var sessionJobs: List<Job> = emptyList()
    private var streamJobs: List<Job> = emptyList()
    /** Una sesión detenida no se reutiliza: si se pidió video mientras se detenía, se crea otra al terminar. */
    @Volatile private var wantsStream = false
    /** Sesiones recreadas seguidas sin llegar a transmitir; acota los reintentos automáticos. */
    private var autoRestarts = 0
    @Volatile private var loggedFirstFrame = false

    init {
        if (GlassesSupport.isReady) {
            scope.launch { Wearables.registrationState.collect { registration = it } }
            scope.launch { selector.activeDeviceFlow().collect { hasActiveDevice = it != null } }
        }
    }

    fun tearDown() {
        stop()
        reader.close()
        scope.cancel()
    }

    fun connect(activity: Activity) {
        errorMessage = null
        if (!GlassesSupport.isReady) return
        Wearables.startRegistration(activity)
    }

    fun disconnect(activity: Activity) {
        errorMessage = null
        stop()
        if (!GlassesSupport.isReady) return
        Wearables.startUnregistration(activity)
    }

    fun start() {
        errorMessage = null
        wantsStream = true
        if (!GlassesSupport.isReady || hasSession) return
        Wearables.createSession(selector)
            .onSuccess { created ->
                session = created
                // Se escucha antes de start() para no perder las primeras transiciones.
                sessionJobs = listOf(
                    scope.launch { created.state.collect { sessionChanged(it) } },
                    scope.launch { created.errors.collect { fail("session", it.description) } },
                )
                sessionState = DeviceSessionState.STARTING
                created.start()
            }
            .onFailure { error, _ ->
                fail("session start", error.description)
                sessionState = DeviceSessionState.IDLE
            }
    }

    fun stop() {
        wantsStream = false
        preview = null
        val current = session ?: return
        if (sessionState == DeviceSessionState.STOPPING) return
        sessionState = DeviceSessionState.STOPPING
        current.stop()
    }

    /** Reintento tras un error: la sesión anterior se cierra y `sessionChanged` crea una nueva. */
    fun restart() {
        errorMessage = null
        autoRestarts = 0
        val current = session
        if (current == null || !hasSession) return start()
        wantsStream = true
        sessionState = DeviceSessionState.STOPPING
        current.stop()
    }

    /** Pedir el permiso abre Meta AI, así que antes se confirma con el usuario. */
    fun permissionResult(status: PermissionStatus) {
        showPermissionPrompt = false
        if (status == PermissionStatus.Granted) beginStream() else errorMessage = L("Meta AI no autorizó la cámara de los lentes.")
    }

    private fun sessionChanged(state: DeviceSessionState) {
        sessionState = state
        when (state) {
            DeviceSessionState.STARTED -> scope.launch { streamIfAllowed() }
            DeviceSessionState.STOPPED -> {
                clearStream()
                sessionJobs.forEach { it.cancel() }
                sessionJobs = emptyList()
                session = null
                if (wantsStream && autoRestarts < MAX_AUTO_RESTARTS) {
                    autoRestarts += 1
                    start()
                } else if (wantsStream) {
                    errorMessage = L("No pudimos conectar con la cámara de los lentes.")
                }
            }
            else -> Unit
        }
    }

    private suspend fun streamIfAllowed() {
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
                if (status == PermissionStatus.Granted) beginStream() else showPermissionPrompt = true
            }
            .onFailure { error, _ -> fail("permission status", error.description) }
    }

    private fun beginStream() {
        val current = session ?: return
        if (current.state.value != DeviceSessionState.STARTED || camera != null || !wantsStream) return
        // Alta (720x1280) a 7 fps es la combinación con la que se verificó la lectura del QR en iOS.
        // Sin comprimir, el SDK entrega cuadros I420 (formato del ejemplo oficial CameraAccess 0.7.0).
        current.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 7))
            .onSuccess { added ->
                camera = added
                val stream = added.stream
                streamJobs = listOf(
                    scope.launch {
                        // El estado repite su valor actual (STOPPED) al suscribirse, antes de start():
                        // solo se limpia si el video ya estuvo activo (igual que el ejemplo oficial).
                        var hasBeenActive = false
                        stream.state.collect { state ->
                            streamState = state
                            val terminal = state == StreamState.STOPPED || state == StreamState.CLOSED
                            if (!terminal) {
                                hasBeenActive = true
                                if (state == StreamState.STREAMING) autoRestarts = 0
                            } else if (hasBeenActive) {
                                clearStream()
                            }
                        }
                    },
                    scope.launch(Dispatchers.Default) { stream.videoStream.collect { handleFrame(it) } },
                    scope.launch { stream.errorStream.collect { fail("stream", it.description) } },
                )
                streamState = StreamState.STARTING
                // Un fallo aquí puede no ser fatal ("the stream can continue regardless"): el estado del
                // video decide si se limpia; solo se registra.
                stream.start().onFailure { error, _ ->
                    Log.i("GlassPay", "glasses stream start warning ${error.description}")
                }
            }
            .onFailure { error, _ ->
                camera = null
                fail("add camera", error.description)
            }
    }

    private fun handleFrame(frame: VideoFrame) {
        if (frame.isCompressed || !wantsStream) return
        val width = frame.width
        val height = frame.height
        val size = i420Size(width, height)
        val buffer = frame.buffer.duplicate()
        if (!loggedFirstFrame) {
            // Diagnóstico del formato, una sola vez: medidas y bytes, nunca la imagen.
            loggedFirstFrame = true
            Log.i("GlassPay", "glasses first frame ${width}x$height bytes=${buffer.remaining()} expectedI420=$size")
        }
        if (size == null) return
        if (buffer.remaining() < size) return
        // El SDK reutiliza el buffer: se copia antes de pasarlo a otro hilo.
        val i420 = ByteArray(size).also { buffer.get(it) }
        val image = lumaBitmap(i420, width, height).asImageBitmap()
        scope.launch { if (wantsStream) preview = image }
        reader.read(i420, width, height) { token ->
            scope.launch { if (wantsStream) onToken(token) }
        }
    }

    private fun clearStream() {
        streamJobs.forEach { it.cancel() }
        streamJobs = emptyList()
        camera?.stop()
        camera = null
        streamState = StreamState.STOPPED
        preview = null
    }

    private companion object {
        const val MAX_AUTO_RESTARTS = 3
    }

    private fun fail(step: String, description: String) {
        // Solo el paso y la descripción del SDK: nunca imágenes ni tokens.
        Log.i("GlassPay", "glasses $step failure $description")
        errorMessage = description
    }
}

/** Lectura de QR fuera del hilo principal. Si llega un cuadro mientras se analiza otro, se descarta. */
private class GlassesQrReader {
    private val busy = AtomicBoolean(false)
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    fun read(i420: ByteArray, width: Int, height: Int, onToken: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val nv21 = i420ToNv21(i420, width, height)
        if (nv21 == null) {
            busy.set(false)
            return
        }
        try {
            scanner.process(InputImage.fromByteArray(nv21, width, height, 0, InputImage.IMAGE_FORMAT_NV21))
                .addOnSuccessListener { barcodes ->
                    barcodes.firstNotNullOfOrNull { QrTokenParser.extractToken(it.rawValue) }?.let(onToken)
                }
                .addOnCompleteListener { busy.set(false) }
        } catch (_: Exception) {
            busy.set(false)
        }
    }

    fun close() = scanner.close()
}

/** Vista previa en escala de grises con el plano Y: basta para apuntar y cuesta poco a 7 fps. */
private fun lumaBitmap(i420: ByteArray, width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val luma = i420[index].toInt() and 0xFF
        pixels[index] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** Bytes de un cuadro I420 (Y completo + U y V a un cuarto), o nulo si las medidas no son válidas. */
fun i420Size(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
    return width * height * 3 / 2
}

/** I420 (Y, U, V en planos) a NV21 (Y + V/U intercalados), el formato que ML Kit acepta en bytes. */
fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray? {
    val size = i420Size(width, height) ?: return null
    if (i420.size < size) return null
    val frameSize = width * height
    val quarter = frameSize / 4
    val nv21 = ByteArray(size)
    System.arraycopy(i420, 0, nv21, 0, frameSize)
    var out = frameSize
    for (index in 0 until quarter) {
        nv21[out++] = i420[frameSize + quarter + index] // V
        nv21[out++] = i420[frameSize + index] // U
    }
    return nv21
}
