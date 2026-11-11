import CoreImage
import Foundation
import MWDATCamera
import MWDATCore
import Observation
import UIKit
import Vision

// Cámara de los lentes (Meta Wearables DAT): el equivalente a QRScannerView cuando se cobra mirando el QR.
// Solo entrega tokens con prefijo "glasspay:". No guarda imágenes ni tokens; el video vive solo en memoria.

/// Lectura de QR fuera del hilo principal. Si llega un cuadro mientras se analiza otro, se descarta.
/// Si la imagen completa no da resultado, reintenta con el centro ampliado y más contraste.
final class GlassesQrReader: @unchecked Sendable {
    private let queue = DispatchQueue(label: "glasspay.glasses.qr", qos: .userInitiated)
    private let lock = NSLock()
    private var busy = false

    func read(_ image: UIImage, onResult: @escaping @Sendable (_ payloads: [String]) -> Void) {
        lock.lock()
        if busy { lock.unlock(); return }
        busy = true
        lock.unlock()
        queue.async { [self] in
            defer { lock.lock(); busy = false; lock.unlock() }
            guard let bitmap = bitmap(from: image) else { return onResult([]) }
            let full = detect(VNImageRequestHandler(cgImage: bitmap, options: [:]))
            if !full.isEmpty { return onResult(full) }
            onResult(detect(VNImageRequestHandler(ciImage: enhancedCenter(of: CIImage(cgImage: bitmap)), options: [:])))
        }
    }

    private func bitmap(from image: UIImage) -> CGImage? {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let size = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        guard size.width > 0, size.height > 0 else { return nil }
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }.cgImage
    }

    private func detect(_ handler: VNImageRequestHandler) -> [String] {
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        do {
            try handler.perform([request])
        } catch {
            logFailure("glasses qr", error)
            return []
        }
        return request.results?.compactMap(\.payloadStringValue) ?? []
    }

    private func enhancedCenter(of image: CIImage) -> CIImage {
        let crop = image.cropped(to: image.extent.insetBy(dx: image.extent.width * 0.2, dy: image.extent.height * 0.2))
        let scaled = crop.transformed(
            by: CGAffineTransform(translationX: -crop.extent.minX, y: -crop.extent.minY)
                .concatenating(CGAffineTransform(scaleX: 2, y: 2))
        )
        return scaled
            .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 0, kCIInputContrastKey: 1.25])
            .applyingFilter("CISharpenLuminance", parameters: [kCIInputSharpnessKey: 0.8])
            .cropped(to: scaled.extent)
    }
}

@MainActor
@Observable
final class GlassesCamera {
    private(set) var registration: RegistrationState
    private(set) var hasActiveDevice = false
    private(set) var sessionState: DeviceSessionState = .idle
    private(set) var streamState: StreamState = .stopped
    private(set) var preview: UIImage?
    private(set) var errorMessage: String?
    var showPermissionPrompt = false

    var isRegistered: Bool { registration == .registered }
    var isPaused: Bool { streamState == .paused }
    var hasSession: Bool {
        switch sessionState {
        case .starting, .started, .paused, .stopping: true
        case .idle, .stopped: false
        }
    }

    @ObservationIgnored var onToken: (String) -> Void = { _ in }
    @ObservationIgnored private let wearables: WearablesInterface = Wearables.shared
    @ObservationIgnored private let selector: AutoDeviceSelector
    @ObservationIgnored private var session: DeviceSession?
    @ObservationIgnored private var camera: MWDATCamera.Camera?
    @ObservationIgnored private let sessionTokens = ListenerTokenBag()
    @ObservationIgnored private let streamTokens = ListenerTokenBag()
    @ObservationIgnored private let reader = GlassesQrReader()
    @ObservationIgnored private var monitors: [Task<Void, Never>] = []
    /// Una sesión detenida no se reutiliza: si se pidió video mientras se detenía, se crea otra al terminar.
    @ObservationIgnored private var wantsStream = false

    init() {
        registration = Wearables.shared.registrationState
        selector = AutoDeviceSelector(wearables: Wearables.shared)
        monitors = [
            Task { [weak self, wearables] in
                for await state in wearables.registrationStateStream() { self?.registration = state }
            },
            Task { [weak self, selector] in
                for await device in selector.activeDeviceStream() { self?.hasActiveDevice = device != nil }
            },
        ]
        Self.removeOldDiagnostics()
    }

    /// Borra las imágenes y el registro que dejó la prueba de viabilidad de los lentes.
    private static func removeOldDiagnostics() {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        try? FileManager.default.removeItem(at: documents.appendingPathComponent("glasses-debug", isDirectory: true))
    }

    /// `deinit` no puede detener una sesión desde el actor principal: lo llama quien la creó al terminar.
    func tearDown() {
        monitors.forEach { $0.cancel() }
        monitors = []
        stop()
    }

    func connect() async {
        errorMessage = nil
        do {
            try await wearables.startRegistration()
        } catch {
            fail("registration", error)
        }
    }

    func disconnect() async {
        errorMessage = nil
        stop()
        do {
            try await wearables.startUnregistration()
        } catch {
            fail("unregistration", error)
        }
    }

    func start() {
        errorMessage = nil
        wantsStream = true
        guard !hasSession else { return }
        do throws(DeviceSessionError) {
            let created = try wearables.createSession(deviceSelector: selector)
            session = created
            created.statePublisher.listen { [weak self] state in
                Task { @MainActor in self?.sessionChanged(state) }
            }.store(in: sessionTokens)
            created.errorPublisher.listen { [weak self] error in
                Task { @MainActor in self?.fail("session", error) }
            }.store(in: sessionTokens)
            sessionState = .starting
            try created.start()
        } catch {
            fail("session start", error)
            sessionState = .idle
            sessionTokens.clear()
            session = nil
        }
    }

    func stop() {
        wantsStream = false
        preview = nil
        guard let session, sessionState != .stopping else { return }
        sessionState = .stopping
        session.stop()
    }

    /// Reintento tras un error: la sesión anterior se cierra y `sessionChanged` crea una nueva.
    func restart() {
        errorMessage = nil
        guard let session, hasSession else { return start() }
        wantsStream = true
        sessionState = .stopping
        session.stop()
    }

    /// Pedir el permiso abre Meta AI, así que antes se confirma con el usuario.
    func grantPermissionAndStream() async {
        showPermissionPrompt = false
        do {
            guard try await wearables.requestPermission(.camera) == .granted else {
                errorMessage = L("Meta AI no autorizó la cámara de los lentes.")
                return
            }
            beginStream()
        } catch {
            fail("permission request", error)
        }
    }

    // MARK: - Sesión y video

    private func sessionChanged(_ state: DeviceSessionState) {
        sessionState = state
        switch state {
        case .started:
            Task { await streamIfAllowed() }
        case .stopped:
            clearStream()
            sessionTokens.clear()
            session = nil
            if wantsStream { start() }
        default:
            break
        }
    }

    private func streamIfAllowed() async {
        do {
            if try await wearables.checkPermissionStatus(.camera) == .granted {
                beginStream()
            } else {
                showPermissionPrompt = true
            }
        } catch {
            fail("permission status", error)
        }
    }

    private func beginStream() {
        guard let session, session.state == .started, camera == nil, wantsStream else { return }
        // Alta a 7 fps es la combinación con la que se verificó la lectura del QR con los Oakley Meta.
        let config = StreamConfiguration(videoCodec: VideoCodec.raw, resolution: StreamingResolution.high, frameRate: 7)
        do {
            guard let created = try session.addCamera(config: config) else {
                errorMessage = L("No se pudo usar la cámara de los lentes.")
                return
            }
            camera = created
            listen(to: created.stream)
            streamState = .starting
            created.stream.start()
        } catch {
            camera = nil
            fail("add camera", error)
        }
    }

    private func listen(to stream: MWDATCamera.Stream) {
        let reader = reader
        stream.statePublisher.listen { [weak self] state in
            Task { @MainActor in
                self?.streamState = state
                if state == .stopped { self?.clearStream() }
            }
        }.store(in: streamTokens)

        stream.videoFramePublisher.listen { [weak self] frame in
            guard let image = frame.makeUIImage() else { return }
            Task { @MainActor in
                guard let self, self.wantsStream else { return }
                self.preview = image
            }
            reader.read(image) { [weak self] payloads in
                guard let token = payloads.lazy.compactMap({ QrTokenParser.extractToken($0) }).first else { return }
                Task { @MainActor in self?.deliver(token) }
            }
        }.store(in: streamTokens)

        stream.errorPublisher.listen { [weak self] error in
            Task { @MainActor in self?.fail("stream", error) }
        }.store(in: streamTokens)
    }

    private func deliver(_ token: String) {
        guard wantsStream else { return }
        onToken(token)
    }

    private func clearStream() {
        streamTokens.clear()
        camera?.stop()
        camera = nil
        streamState = .stopped
        preview = nil
    }

    private func fail(_ step: String, _ error: Error) {
        logFailure("glasses \(step)", error)
        errorMessage = error.localizedDescription
    }
}
