import CoreImage
import Foundation
import MWDATCamera
import MWDATCore
import Observation
import UIKit
import Vision

// Prueba de viabilidad (Entrega 1 de lentes): ¿se lee el QR de GlassPay en el video de los Oakley Meta?
// No llama al servidor. Solo guarda imágenes si el usuario pulsa "Guardar diagnóstico".

enum GlassesResolution: String, CaseIterable, Identifiable {
    case low = "Baja"
    case medium = "Media"
    case high = "Alta"

    var id: String { rawValue }

    var streaming: StreamingResolution {
        switch self {
        case .low: .low
        case .medium: .medium
        case .high: .high
        }
    }
}

struct GlassesQrResult: Sendable {
    let payloads: [String]
    /// Lado del código más grande encontrado, en píxeles del cuadro original. Nil si no se vio ninguno.
    let sizePx: Int?
}

/// Lectura de QR fuera del hilo principal. Si llega un cuadro mientras se analiza otro, se descarta;
/// una foto (`always`) siempre se analiza. Si la imagen completa no da resultado, reintenta con el
/// centro ampliado y más contraste, porque el video comprimido deja códigos pequeños poco nítidos.
final class GlassesQrReader: @unchecked Sendable {
    private let queue = DispatchQueue(label: "glasspay.glasses.qr", qos: .userInitiated)
    private let lock = NSLock()
    private var busy = false

    func read(_ image: UIImage, always: Bool = false, onResult: @escaping @Sendable (GlassesQrResult) -> Void) {
        if !always {
            lock.lock()
            if busy { lock.unlock(); return }
            busy = true
            lock.unlock()
        }
        queue.async { [self] in
            defer {
                if !always { lock.lock(); busy = false; lock.unlock() }
            }
            // El cuadro puede venir respaldado por un búfer de video: se pinta en un mapa de bits normal.
            guard let bitmap = bitmap(from: image) else {
                note(image, "sin mapa de bits")
                return onResult(GlassesQrResult(payloads: [], sizePx: nil))
            }
            let full = detect(VNImageRequestHandler(cgImage: bitmap, options: [:]), extent: CGSize(width: bitmap.width, height: bitmap.height), scale: 1)
            let result: GlassesQrResult
            if full.payloads.isEmpty {
                let enhanced = enhancedCenter(of: CIImage(cgImage: bitmap), zoom: 2)
                let second = detect(VNImageRequestHandler(ciImage: enhanced, options: [:]), extent: enhanced.extent.size, scale: 2)
                result = second.sizePx == nil ? full : second
            } else {
                result = full
            }
            note(image, "\(bitmap.width)x\(bitmap.height) qr=\(result.sizePx.map(String.init) ?? "no") descifrados=\(result.payloads.count) validos=\(result.payloads.filter { QrTokenParser.extractToken($0) != nil }.count)")
            onResult(result)
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

    private func detect(_ handler: VNImageRequestHandler, extent: CGSize, scale: CGFloat) -> GlassesQrResult {
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        do {
            try handler.perform([request])
        } catch {
            noteError(error)
            return GlassesQrResult(payloads: [], sizePx: nil)
        }
        guard let results = request.results, !results.isEmpty else { return GlassesQrResult(payloads: [], sizePx: nil) }
        let side = results.map { max($0.boundingBox.width * extent.width, $0.boundingBox.height * extent.height) }.max() ?? 0
        return GlassesQrResult(payloads: results.compactMap(\.payloadStringValue), sizePx: Int(side / scale))
    }

    // Registro de diagnóstico sin tokens: como máximo una línea por segundo, y siempre los errores.
    private var lastNote = Date.distantPast

    private func note(_ image: UIImage, _ text: String) {
        guard Date().timeIntervalSince(lastNote) >= 1 else { return }
        lastNote = Date()
        write("cg=\(image.cgImage != nil) ci=\(image.ciImage != nil) orient=\(image.imageOrientation.rawValue) \(text)")
    }

    private func noteError(_ error: Error) {
        write("ERROR Vision: \(error.localizedDescription)")
    }

    private func write(_ line: String) {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let folder = documents.appendingPathComponent("glasses-debug", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("lector.log")
        let data = Data("\(Date().timeIntervalSince1970) \(line)\n".utf8)
        if let handle = try? FileHandle(forWritingTo: file) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: file)
        }
    }

    private func enhancedCenter(of image: CIImage, zoom: CGFloat) -> CIImage {
        let crop = image.cropped(to: image.extent.insetBy(dx: image.extent.width * 0.2, dy: image.extent.height * 0.2))
        let moved = CGAffineTransform(translationX: -crop.extent.minX, y: -crop.extent.minY).scaledBy(x: 1, y: 1)
        let scaled = crop.transformed(by: moved.concatenating(CGAffineTransform(scaleX: zoom, y: zoom)))
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
    private(set) var deviceStatus = "Sin lentes detectados"
    private(set) var sessionState: DeviceSessionState = .idle
    private(set) var streamState: StreamState = .stopped
    private(set) var preview: UIImage?
    private(set) var errorMessage: String?
    var showPermissionPrompt = false
    var resolution: GlassesResolution = .medium
    var frameRate: UInt = 15

    private(set) var framesReceived = 0
    private(set) var framesAnalyzed = 0
    private(set) var ignoredCodes = 0
    private(set) var reads: [TimeInterval] = []
    private(set) var measuring = false
    private(set) var photoResult: String?
    private(set) var frameSize = "—"
    private(set) var framesWithCode = 0
    private(set) var lastCodeSize: Int?
    private(set) var readerSelfTest = "Comprobando…"
    private(set) var diagnosticMessage: String?

    var isRegistered: Bool { registration == .registered }
    var isStreaming: Bool { streamState == .streaming }
    var isBusy: Bool {
        sessionState == .starting || sessionState == .stopping
            || streamState == .starting || streamState == .waitingForDevice || streamState == .stopping
    }
    var hasSession: Bool {
        switch sessionState {
        case .starting, .started, .paused, .stopping: true
        case .idle, .stopped: false
        }
    }

    @ObservationIgnored private let wearables: WearablesInterface = Wearables.shared
    @ObservationIgnored private let selector: AutoDeviceSelector
    @ObservationIgnored private var session: DeviceSession?
    @ObservationIgnored private var camera: MWDATCamera.Camera?
    @ObservationIgnored private let sessionTokens = ListenerTokenBag()
    @ObservationIgnored private let streamTokens = ListenerTokenBag()
    @ObservationIgnored private let reader = GlassesQrReader()
    @ObservationIgnored private var monitors: [Task<Void, Never>] = []
    @ObservationIgnored private var measureStart: Date?
    @ObservationIgnored private var savesNextPhoto = false

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
            Task { [weak self] in self?.runReaderSelfTest() },
            Task { [weak self] in
                while !Task.isCancelled {
                    self?.refreshDeviceStatus()
                    try? await Task.sleep(for: .seconds(1))
                }
            },
        ]
    }

    /// Enlace, puestos y patillas de los lentes, para distinguir un problema de conexión de uno de la app.
    private func refreshDeviceStatus() {
        guard let id = selector.activeDevice ?? wearables.devices.first,
              let device = wearables.deviceForIdentifier(id) else {
            deviceStatus = "Sin lentes detectados"
            return
        }
        deviceStatus = "\(device.nameOrId()): enlace \(device.linkState), puestos \(device.donState), "
            + "patillas \(device.hingeState), temperatura \(device.thermalLevel), \(device.compatibility().displayString), batería \(device.batteryLevel.map(String.init) ?? "?")"
    }

    /// Lee un QR generado aquí mismo: si falla, el problema es el lector y no el video de los lentes.
    private func runReaderSelfTest() {
        let payload = "glasspay:" + String(repeating: "aZ3_", count: 11).prefix(43)
        let filter = CIFilter(name: "CIQRCodeGenerator")
        filter?.setValue(payload.data(using: .utf8), forKey: "inputMessage")
        guard let output = filter?.outputImage?.transformed(by: CGAffineTransform(scaleX: 6, y: 6)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else {
            readerSelfTest = "No se pudo generar el QR de prueba"
            return
        }
        reader.read(UIImage(cgImage: cgImage), always: true) { [weak self] result in
            let passed = result.payloads.contains(payload)
            Task { @MainActor in self?.readerSelfTest = passed ? "Correcta" : "FALLA" }
        }
    }

    /// Solo a petición del usuario: guarda el cuadro actual y una foto dentro de la app para revisarlos.
    func saveDiagnostic() {
        guard let frame = preview?.pngData(), let folder = diagnosticFolder() else {
            diagnosticMessage = "Todavía no hay video que guardar."
            return
        }
        do {
            try frame.write(to: folder.appendingPathComponent("cuadro-\(Int(Date().timeIntervalSince1970)).png"))
            savesNextPhoto = true
            diagnosticMessage = "Cuadro guardado. Tomando foto…"
            capturePhoto()
        } catch {
            fail("diagnostic", error)
        }
    }

    private func savePhotoIfRequested(_ data: Data) {
        guard savesNextPhoto else { return }
        savesNextPhoto = false
        guard let folder = diagnosticFolder() else { return }
        let saved = (try? data.write(to: folder.appendingPathComponent("foto-\(Int(Date().timeIntervalSince1970)).jpg"))) != nil
        diagnosticMessage = saved ? "Listo: cuadro y foto guardados." : "No se pudo guardar la foto."
    }

    private func diagnosticFolder() -> URL? {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let folder = documents.appendingPathComponent("glasses-debug", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// Lo llama la pantalla al salir: `deinit` no puede detener una sesión desde el actor principal.
    func tearDown() {
        monitors.forEach { $0.cancel() }
        monitors = []
        session?.stop()
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
        session?.stop()
        do {
            try await wearables.startUnregistration()
        } catch {
            fail("unregistration", error)
        }
    }

    /// Conecta la sesión; el video arranca solo al quedar en `.started`.
    func start() {
        errorMessage = nil
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
        guard let session else { return }
        sessionState = .stopping
        session.stop()
    }

    /// Pedir el permiso abre Meta AI, así que se confirma antes con el usuario.
    func grantPermissionAndStream() async {
        showPermissionPrompt = false
        do {
            guard try await wearables.requestPermission(.camera) == .granted else {
                errorMessage = "Meta AI no autorizó la cámara de los lentes."
                return
            }
            beginStream()
        } catch {
            fail("permission request", error)
        }
    }

    func startMeasure() {
        measureStart = Date()
        measuring = true
    }

    func resetMeasures() {
        reads = []
        ignoredCodes = 0
        framesAnalyzed = 0
        framesReceived = 0
        framesWithCode = 0
        lastCodeSize = nil
        measuring = false
        measureStart = nil
        photoResult = nil
    }

    /// Alternativa si el video no basta: una foto de los lentes tiene más detalle que un cuadro comprimido.
    func capturePhoto() {
        photoResult = "Tomando foto…"
        if camera?.stream.capturePhoto(format: .jpeg) != true { photoResult = "No se pudo tomar la foto." }
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
        guard let session, session.state == .started, camera == nil else { return }
        let config = StreamConfiguration(videoCodec: VideoCodec.raw, resolution: resolution.streaming, frameRate: frameRate)
        do {
            guard let created = try session.addCamera(config: config) else {
                errorMessage = "No se pudo crear la cámara de los lentes."
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
                self?.preview = image
                self?.frameSize = "\(Int(image.size.width * image.scale))×\(Int(image.size.height * image.scale))"
                self?.framesReceived += 1
            }
            reader.read(image) { [weak self] result in
                Task { @MainActor in self?.analyzed(result) }
            }
        }.store(in: streamTokens)

        stream.errorPublisher.listen { [weak self] error in
            Task { @MainActor in self?.fail("stream", error) }
        }.store(in: streamTokens)

        stream.photoDataPublisher.listen { [weak self] photo in
            Task { @MainActor in self?.savePhotoIfRequested(photo.data) }
            guard let image = UIImage(data: photo.data) else {
                Task { @MainActor in self?.photoResult = "La foto no se pudo leer." }
                return
            }
            let size = "\(Int(image.size.width * image.scale))×\(Int(image.size.height * image.scale))"
            reader.read(image, always: true) { [weak self] result in
                let found = result.payloads.contains { QrTokenParser.extractToken($0) != nil }
                let code = result.sizePx.map { "QR de \($0) px" } ?? "ningún QR visible"
                Task { @MainActor in
                    self?.photoResult = "Foto \(size), \(code): \(found ? "leído" : "no leído")."
                }
            }
        }.store(in: streamTokens)
    }

    private func analyzed(_ result: GlassesQrResult) {
        framesAnalyzed += 1
        if let size = result.sizePx {
            framesWithCode += 1
            lastCodeSize = size
        }
        let payloads = result.payloads
        let valid = payloads.contains { QrTokenParser.extractToken($0) != nil }
        if !payloads.isEmpty && !valid { ignoredCodes += 1 }
        guard valid, measuring, let measureStart else { return }
        reads.append(Date().timeIntervalSince(measureStart))
        measuring = false
        self.measureStart = nil
    }

    private func clearStream() {
        streamTokens.clear()
        camera?.stop()
        camera = nil
        streamState = .stopped
        preview = nil
        measuring = false
        measureStart = nil
    }

    private func fail(_ step: String, _ error: Error) {
        logFailure("glasses \(step)", error)
        errorMessage = error.localizedDescription
    }
}
