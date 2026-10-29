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

@MainActor
final class VoiceRecognizer {
    var onResult: (String) -> Void = { _ in }
    var onReady: () -> Void = {}
    var onEnd: () -> Void = {}
    var onPartial: () -> Void = {}
    var onError: (VoiceErrorKind) -> Void = { _ in }

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "es-MX"))
    private let audioEngine = AVAudioEngine()
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
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.taskHint = .dictation
            self.request = request

            let input = audioEngine.inputNode
            let format = input.outputFormat(forBus: 0)
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

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// `onDone` se llama al terminar o al interrumpirse, igual que en Android.
    func speak(_ text: String, onDone: @escaping () -> Void = {}) {
        stop()
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "es-MX")
        completions[ObjectIdentifier(utterance)] = onDone
        synthesizer.speak(utterance)
    }

    func stop() {
        // Detener sin disparar callbacks pendientes: el flujo que los pidió ya cambió.
        completions.removeAll()
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
        completions.removeValue(forKey: id)?()
    }
}
