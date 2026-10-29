import BackgroundTasks
import CoreImage.CIFilterBuiltins
import LocalAuthentication
import Network
import Observation
import UIKit
import UserNotifications

// Servicios del dispositivo: bloqueo con Face ID / código (DeviceAuthenticator.kt), QR propio
// (QrEncoder.kt con Core Image en lugar de ZXing), red (banner sin conexión), vibración y la revisión
// en segundo plano de programaciones (ScheduledCheckWorker.kt con BGTaskScheduler en lugar de WorkManager).

enum DeviceAuthAvailability { case available, notConfigured, unsupported }

enum DeviceAuthenticator {
    /// La app nunca recibe datos biométricos: solo el resultado del sistema.
    static var availability: DeviceAuthAvailability {
        var error: NSError?
        if LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) { return .available }
        if let code = error.map({ LAError.Code(rawValue: $0.code) }), code == .passcodeNotSet { return .notConfigured }
        return .unsupported
    }

    /// Face ID / Touch ID con respaldo al código del teléfono. Cancelar o fallar devuelve false.
    static func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        context.localizedCancelTitle = "Cancelar"
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}

enum QRCodeImage {
    /// QR nítido para mostrar en pantalla; el contenido es solo "glasspay:<token>", sin datos personales.
    static func make(_ text: String, scale: CGFloat = 12) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

@MainActor
@Observable
final class NetworkMonitor {
    private(set) var online = true
    @ObservationIgnored private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.online = satisfied }
        }
        monitor.start(queue: DispatchQueue(label: "glasspay.network"))
    }

    deinit { monitor.cancel() }
}

enum Haptics {
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func tap() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
}

/// Revisión periódica de programaciones que esperan confirmación, con la app cerrada.
/// iOS decide cuándo correrla (no es exacta, igual que WorkManager). La notificación no lleva montos ni nombres.
enum ScheduledCheck {
    static let taskIdentifier = "com.glasspay.ios.scheduled-check"
    private static let lastCountKey = "glasspay.scheduled.lastAwaitingCount"

    static func register(api: PaymentAPI) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { return task.setTaskCompleted(success: false) }
            schedule()
            let work = Task { await run(api: api, task: refresh) }
            refresh.expirationHandler = { work.cancel() }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
        UserDefaults.standard.removeObject(forKey: lastCountKey)
    }

    static func requestNotificationPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    private static func run(api: PaymentAPI, task: BGAppRefreshTask) async {
        guard let session = CollectorSessionStore().load() else { return task.setTaskCompleted(success: true) }
        do {
            let awaiting = try await api.scheduled(session).filter { $0.state == "esperando_confirmacion" }.count
            let previous = UserDefaults.standard.integer(forKey: lastCountKey)
            UserDefaults.standard.set(awaiting, forKey: lastCountKey)
            if awaiting > previous { await notify() }
            task.setTaskCompleted(success: true)
        } catch {
            // 401 u otro fallo: no hacer nada; la app pedirá una cuenta nueva al abrirse.
            task.setTaskCompleted(success: false)
        }
    }

    private static func notify() async {
        let content = UNMutableNotificationContent()
        content.title = "GlassPay"
        content.body = "Tienes algo programado que espera tu confirmación."
        content.sound = .default
        let request = UNNotificationRequest(identifier: "scheduled-awaiting", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
