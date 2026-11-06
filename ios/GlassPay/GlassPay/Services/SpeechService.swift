import AVFoundation
import Speech

// Equivalente iOS de SpeechTools.kt: reconocimiento es-MX con resultados parciales y síntesis de voz.
// Nunca se registra el texto dictado. El micrófono solo se abre cuando termina de hablar el TTS (anti-eco).

enum VoiceErrorKind {
    case permissionDenied, busy, noMatch, speechTimeout, unavailable, other

    var message: String {
        switch self {
        case .permissionDenied: return "Permiso de micrófono denegado. Usa la entrada por texto."
        case .busy: return "El servicio de voz está ocupado. Intenta de nuevo."
        case .noMatch: return "No entendí la frase. Intenta de nuevo."
        case .speechTimeout: return "No detecté voz. Intenta de nuevo."
        case .unavailable: return "El reconocimiento de voz no está disponible. Usa la entrada por texto."
        case .other: return "No pudimos usar el reconocimiento de voz. Usa la entrada por texto."
        }
    }
}

/// Ruta de audio compartida por el dictado y la voz de la app: los lentes (Bluetooth) si están enlazados
/// y el usuario no eligió el iPhone. Se libera antes de volver a escanear, porque el video de los lentes
/// necesita el ancho de banda de Bluetooth.
@MainActor
enum AudioRoute {
    private static let preferenceKey = "glasspay.prefersGlassesAudio"

    static var prefersGlasses: Bool {
        get { UserDefaults.standard.object(forKey: preferenceKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: preferenceKey) }
    }

    static func activate(recording: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        var options: AVAudioSession.CategoryOptions = [.defaultToSpeaker, .duckOthers]
        if prefersGlasses { options.insert(.allowBluetoothHFP) }
        try session.setCategory(.playAndRecord, mode: .default, options: options)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        let bluetooth = prefersGlasses ? session.availableInputs?.first { $0.portType == .bluetoothHFP } : nil
        try session.setPreferredInput(bluetooth)
        if recording && bluetooth == nil { try session.setMode(.measurement) }
    }

    static func release() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    static var usesBluetooth: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .bluetoothHFP }
    }

    /// Por dónde se escucha ahora mismo; no incluye datos del usuario.
    static var inputLabel: String {
        let input = AVAudioSession.sharedInstance().currentRoute.inputs.first
        return input?.portType == .bluetoothHFP ? input?.portName ?? "Bluetooth" : "el iPhone"
    }
}

@MainActor
final class VoiceRecognizer {
    var onResult: (String) -> Void = { _ in }
    var onReady: () -> Void = {}
    var onEnd: () -> Void = {}
    var onPartial: () -> Void = {}
    var onError: (VoiceErrorKind) -> Void = { _ in }

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "es-MX"))
    private var audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var latestText = ""
    private var resultDelivered = false
    private var released = false

    /// Silencio tras la última palabra antes de cerrar la frase, y espera máxima si nadie habla.
    private let endOfSpeechSilence: TimeInterval = 1.4
    private let noSpeechTimeout: TimeInterval = 6

    var isAvailable: Bool { recognizer?.isAvailable == true }

    static func requestPermissions() async -> Bool {
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
        }
        guard speech else { return false }
        return await AVAudioApplication.requestRecordPermission()
    }

    static var hasPermissions: Bool {
        SFSpeechRecognizer.authorizationStatus() == .authorized && AVAudioApplication.shared.recordPermission == .granted
    }

    func start() {
        stopEngine()
        released = false
        resultDelivered = false
        latestText = ""
        guard let recognizer, recognizer.isAvailable else {
            onError(.unavailable)
            return
        }
        do {
            try AudioRoute.activate(recording: true)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.taskHint = .dictation
            self.request = request

            // Motor nuevo en cada dictado: al cambiar de ruta, uno reutilizado conserva el formato anterior.
            audioEngine = AVAudioEngine()
            let input = audioEngine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                stopEngine()
                onError(.busy)
                return
            }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            audioEngine.prepare()
            try audioEngine.start()

            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let text = result?.bestTranscription.formattedString
                let isFinal = result?.isFinal == true
                Task { @MainActor in self?.handle(text: text, isFinal: isFinal, error: error) }
            }
            onReady()
            scheduleSilenceTimer(after: noSpeechTimeout)
        } catch {
            stopEngine()
            onError(.busy)
        }
    }

    func cancel() {
        stopEngine()
    }

    func release() {
        released = true
        stopEngine()
    }

    private func handle(text: String?, isFinal: Bool, error: Error?) {
        guard !released, !resultDelivered else { return }
        if let text, !text.isEmpty {
            latestText = text
            onPartial()
            scheduleSilenceTimer(after: endOfSpeechSilence)
        }
        if isFinal {
            finish()
        } else if error != nil {
            if latestText.isEmpty {
                stopEngine()
                onError(.noMatch)
            } else {
                finish()
            }
        }
    }

    private func scheduleSilenceTimer(after interval: TimeInterval) {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.silenceElapsed() }
        }
    }

    private func silenceElapsed() {
        guard !released, !resultDelivered else { return }
        if latestText.isEmpty {
            stopEngine()
            onError(.speechTimeout)
        } else {
            finish()
        }
    }

    private func finish() {
        guard !resultDelivered else { return }
        let text = latestText.trimmingCharacters(in: .whitespacesAndNewlines)
        onEnd()
        stopEngine()
        if text.isEmpty {
            onError(.noMatch)
        } else {
            resultDelivered = true
            onResult(text)
        }
    }

    private func stopEngine() {
        silenceTimer?.invalidate()
        silenceTimer = nil
        if audioEngine.isRunning { audioEngine.stop() }
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        task = nil
        request = nil
    }
}

@MainActor
final class SpeechOutput: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private var completions: [ObjectIdentifier: () -> Void] = [:]
    private var pendingCompletion: Task<Void, Never>?
    /// El audio por Bluetooth termina de sonar después del aviso del sintetizador. Sin esta pausa, el
    /// micrófono de los lentes capta el final de la frase (anti-eco).
    private let bluetoothEchoDelay: Duration = .milliseconds(700)

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// `onDone` se llama al terminar o al interrumpirse, igual que en Android.
    func speak(_ text: String, onDone: @escaping () -> Void = {}) {
        stop()
        try? AudioRoute.activate(recording: false)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "es-MX")
        completions[ObjectIdentifier(utterance)] = onDone
        synthesizer.speak(utterance)
    }

    func stop() {
        // Detener sin disparar callbacks pendientes: el flujo que los pidió ya cambió.
        completions.removeAll()
        pendingCompletion?.cancel()
        pendingCompletion = nil
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.complete(id) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.complete(id) }
    }

    private func complete(_ id: ObjectIdentifier) {
        guard let done = completions.removeValue(forKey: id) else { return }
        guard AudioRoute.usesBluetooth else { return done() }
        pendingCompletion = Task { [bluetoothEchoDelay] in
            try? await Task.sleep(for: bluetoothEchoDelay)
            if !Task.isCancelled { done() }
        }
    }
}
