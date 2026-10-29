import Foundation
import Observation
import SwiftUI

// Raíz de la app (en Android, GlassPayRoot en MainActivity.kt): sesión, pestañas, rutas, bloqueo y ojo.

/// Lo que cada ViewModel necesita para hablar con el servidor como el cobrador actual.
struct AppContext {
    let api: PaymentAPI
    let session: CollectorSession
    let invalidate: @MainActor (String) -> Void
}

enum LoadState<Value> {
    case loading
    case loaded(Value)
    case failed

    var value: Value? { if case let .loaded(value) = self { return value }; return nil }
}

enum Route: Hashable {
    case myQr, stats, scheduled, assistant, payoutEmail, addContact
    case send(Contact?)
}

@MainActor
@Observable
final class AppViewModel {
    let api = PaymentAPI()
    private(set) var session: CollectorSession?
    private(set) var loaded = false
    private(set) var welcomeMessage: String?
    private(set) var saveNotice: String?
    private(set) var tab: AppTab = .home
    var homePath: [Route] = [] { didSet { discardAssistantIfClosed() } }
    var contactsPath: [Route] = []

    private(set) var locked = true
    private(set) var authAvailability = DeviceAuthenticator.availability
    var amountsVisible = false
    private(set) var charge: ChargeViewModel?
    private(set) var assistant: AssistantViewModel?

    @ObservationIgnored private let store = CollectorSessionStore()
    @ObservationIgnored private var prompting = false
    @ObservationIgnored private var backgroundedAt: Date?

    /// Bloqueada solo si hay sesión y el teléfono tiene un bloqueo configurado (si no, Inicio lo avisa).
    var lockActive: Bool { session != nil && locked && authAvailability == .available }
    var showsTabBar: Bool {
        guard tab == .charge, let charge else { return true }
        return allowsTabSwitch(resolve: charge.resolveState, charge: charge.chargeState)
    }

    var context: AppContext? {
        guard let session else { return nil }
        return AppContext(api: api, session: session, invalidate: { [weak self] in self?.invalidateSession($0) })
    }

    func start() async {
        session = store.load()
        loaded = true
        if session != nil { ScheduledCheck.schedule() }
        do { try await api.warmUp() } catch { logFailure("health", error) }
    }

    func accountCreated(_ created: CollectorSession) {
        do {
            try store.save(created)
            saveNotice = nil
        } catch {
            saveNotice = "No pudimos guardar tu sesión en este teléfono."
        }
        welcomeMessage = nil
        locked = false
        tab = .home
        session = created
        ScheduledCheck.schedule()
    }

    func invalidateSession(_ message: String) {
        welcomeMessage = message
        session = nil
        charge?.tearDown()
        charge = nil
        assistant = nil
        homePath = []
        contactsPath = []
        tab = .home
        ScheduledCheck.cancel()
        store.clear()
    }

    // MARK: - Pestañas y rutas

    var tabBinding: Binding<AppTab> {
        Binding(get: { self.tab }, set: { self.selectTab($0) })
    }

    func selectTab(_ next: AppTab) {
        guard showsTabBar || next == tab else { return }
        if next != .charge, charge != nil {
            charge?.tearDown()
            charge = nil
        }
        saveNotice = nil
        homePath = []
        contactsPath = []
        tab = next
        if next == .charge, charge == nil, let context {
            charge = ChargeViewModel(context: context, onOpenMovements: { [weak self] in self?.selectTab(.movements) })
            charge?.locked = lockActive
        }
    }

    func open(_ route: Route) {
        if route == .assistant, assistant == nil, let context { assistant = AssistantViewModel(context: context) }
        if tab == .contacts { contactsPath.append(route) } else { homePath.append(route) }
    }

    /// Cobrar a un contacto: pestaña Cobrar con el pagador ya identificado (mismo flujo de confirmación).
    func chargeContact(_ resolution: PayerResolution) {
        selectTab(.charge)
        charge?.applyContactResolution(resolution)
    }

    private func discardAssistantIfClosed() {
        // La conversación solo vive mientras el asistente está abierto.
        if !homePath.contains(.assistant) { assistant = nil }
    }

    // MARK: - Bloqueo

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .background:
            backgroundedAt = Date()
            amountsVisible = false
        case .active:
            authAvailability = DeviceAuthenticator.availability
            if shouldLockOnResume(backgroundedAt: backgroundedAt, now: Date()) { locked = true }
            backgroundedAt = nil
            charge?.locked = lockActive
            if lockActive { Task { await unlock() } }
        default:
            break
        }
    }

    func unlock() async {
        guard lockActive, !prompting else { return }
        prompting = true
        let ok = await DeviceAuthenticator.authenticate(reason: "Desbloquea GlassPay con Face ID o el código de tu teléfono")
        prompting = false
        if ok { locked = false }
        charge?.locked = lockActive
    }
}
