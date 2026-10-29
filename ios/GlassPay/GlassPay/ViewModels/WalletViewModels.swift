import Foundation
import Observation
import UIKit

// ViewModels de Bienvenida, Inicio, Movimientos, Contactos, Agregar con QR, Mi QR y Correo de cobro
// (en Android, la lógica de WelcomeScreen, HomeScreen, MovementsScreen, ContactsScreen, AddContactScreen,
// MyQrScreen y PayoutEmailScreen).

@MainActor
@Observable
final class WelcomeViewModel {
    var name = ""
    private(set) var creating = false
    private(set) var waking = false
    private(set) var error: String?

    func createAccount(api: PaymentAPI, onCreated: @escaping (CollectorSession) -> Void) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !creating else { return }
        creating = true
        error = nil
        Task {
            // Si tarda, probablemente Render está despertando: se avisa sin alarmar.
            let wakingHint = Task { try? await Task.sleep(for: .seconds(3)); if !Task.isCancelled { waking = true } }
            defer { wakingHint.cancel(); creating = false; waking = false }
            do {
                onCreated(try await api.createCollector(displayName: trimmed))
            } catch {
                logFailure("createCollector", error)
                self.error = "No pudimos crear tu cuenta. Revisa tu conexión e inténtalo de nuevo."
            }
        }
    }
}

@MainActor
@Observable
final class HomeViewModel {
    private(set) var profile: LoadState<CollectorProfile> = .loading
    private(set) var refreshing = false
    private(set) var incoming: [IncomingRequest] = []
    private(set) var reminders: [Reminder] = []
    private(set) var awaitingConfirmation = 0
    private(set) var incomingMessage: String?
    private(set) var rejecting = false
    var pendingReject: IncomingRequest?

    var visibleIncoming: [IncomingRequest] {
        incoming.filter { $0.state == "pendiente_de_pagador" || $0.state == "aprobada" }
    }

    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func reload() async {
        if profile.value != nil { refreshing = true } else { profile = .loading }
        defer { refreshing = false }
        do {
            profile = .loaded(try await context.api.collectorProfile(context.session))
        } catch {
            guard !error.isCancellation else { return }
            logFailure("profile", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            profile = .failed
        }
        await loadScheduledSummary()
    }

    private func loadScheduledSummary() async {
        do {
            awaitingConfirmation = try await context.api.scheduled(context.session).filter { $0.state == "esperando_confirmacion" }.count
        } catch {
            logFailure("scheduled summary", error)
        }
    }

    /// "Por pagar" y recordatorios: cada 5 s mientras Inicio está visible y la app desbloqueada.
    func pollIncoming() async {
        while !Task.isCancelled {
            do { reminders = try await context.api.reminders(context.session) } catch { logFailure("reminders", error) }
            do {
                incoming = try await context.api.incomingRequests(context.session)
            } catch {
                logFailure("incoming", error)
                if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            }
            try? await Task.sleep(for: .seconds(5))
        }
    }

    /// Solo se abre PayPal Sandbox con https y host exacto.
    func approve(_ request: IncomingRequest) {
        guard isAllowedApproveUrl(request.approveUrl), let url = request.approveUrl.flatMap(URL.init(string:)) else {
            incomingMessage = "Enlace no válido."
            return
        }
        UIApplication.shared.open(url) { [weak self] opened in
            Task { @MainActor in
                self?.incomingMessage = opened
                    ? "Abriste PayPal. Cuando se confirme, desaparecerá de Por pagar."
                    : "No hay navegador disponible."
            }
        }
    }

    func reject(_ request: IncomingRequest) {
        guard !rejecting else { return }
        rejecting = true
        Task {
            defer { rejecting = false }
            do {
                try await context.api.rejectIncoming(context.session, requestId: request.requestId)
                incomingMessage = nil
                await reload()
            } catch {
                logFailure("reject", error)
                switch error.apiFailure {
                case .unauthorized: context.invalidate(sessionInvalidMessage)
                case .notFound: await reload()
                default: incomingMessage = "No pudimos rechazar el cobro. Revisa tu conexión."
                }
            }
        }
    }

    func dismiss(_ reminder: Reminder) {
        Task {
            do {
                try await context.api.dismissReminder(context.session, reminderId: reminder.reminderId)
            } catch {
                logFailure("dismiss reminder", error)
                if error.apiFailure == .unauthorized { context.invalidate(sessionInvalidMessage) }
            }
            reminders.removeAll { $0.reminderId == reminder.reminderId }
        }
    }
}

@MainActor
@Observable
final class MovementsViewModel {
    private(set) var state: LoadState<[Movement]> = .loading
    var selected: Movement?
    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func reload() async {
        if state.value == nil { state = .loading }
        do {
            state = .loaded(try await context.api.movements(context.session))
        } catch {
            guard !error.isCancellation else { return }
            logFailure("movements", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            state = .failed
        }
    }
}

@MainActor
@Observable
final class ContactsViewModel {
    private(set) var state: LoadState<[Contact]> = .loading
    private(set) var message: String?
    private(set) var busy = false
    var pendingDelete: Contact?
    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func reload() async {
        if state.value == nil { state = .loading }
        do {
            state = .loaded(try await context.api.listContacts(context.session))
        } catch {
            guard !error.isCancellation else { return }
            logFailure("contacts", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            state = .failed
        }
    }

    func charge(_ contact: Contact, onResolved: @escaping (PayerResolution) -> Void) {
        guard !busy else { return }
        busy = true
        message = nil
        Task {
            defer { busy = false }
            do {
                onResolved(try await context.api.contactResolution(context.session, contactId: contact.contactId))
            } catch {
                logFailure("contactResolution", error)
                switch error.apiFailure {
                case .unauthorized: context.invalidate(sessionInvalidMessage)
                case .notFound:
                    message = "Este contacto ya no existe."
                    await reload()
                case .rateLimited: message = "Demasiados intentos. Espera unos segundos."
                default: message = "No pudimos preparar el cobro. Revisa tu conexión."
                }
            }
        }
    }

    func delete(_ contact: Contact) {
        Task {
            do {
                try await context.api.deleteContact(context.session, contactId: contact.contactId)
                await reload()
            } catch {
                logFailure("deleteContact", error)
                switch error.apiFailure {
                case .unauthorized: context.invalidate(sessionInvalidMessage)
                case .notFound: await reload()
                case .rateLimited: message = "Demasiados intentos. Espera unos segundos."
                default: message = "No pudimos eliminar el contacto. Revisa tu conexión."
                }
            }
        }
    }
}

@MainActor
@Observable
final class AddContactViewModel {
    enum Phase: Equatable { case scanning, adding, done(message: String, isError: Bool) }

    private(set) var phase: Phase = .scanning
    private(set) var cameraGranted = QRScannerView.cameraAuthorized
    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func requestCamera() async {
        if !cameraGranted { cameraGranted = await QRScannerView.requestCamera() }
    }

    func handle(_ token: String) {
        guard phase == .scanning else { return }
        phase = .adding
        Task {
            do {
                let resolution = try await context.api.resolveQrToken(context.session, token: token, timeout: 15)
                if resolution.contactId != nil {
                    phase = .done(message: "\(resolution.displayName) ya está en tus contactos", isError: false)
                } else {
                    _ = try await context.api.saveContact(context.session, resolutionId: resolution.resolutionId)
                    phase = .done(message: "\(resolution.displayName) se agregó a tus contactos", isError: false)
                }
                Haptics.success()
            } catch {
                logFailure("addContact", error)
                switch error.apiFailure {
                case .unauthorized: context.invalidate(sessionInvalidMessage)
                case .notFound: phase = .done(message: "Ese QR ya no es válido. Pide que lo actualicen.", isError: true)
                case .conflict: phase = .done(message: "Ese es tu propio QR.", isError: true)
                case .rateLimited: phase = .done(message: "Demasiados intentos. Espera unos segundos.", isError: true)
                default: phase = .done(message: "Revisa tu conexión.", isError: true)
                }
            }
        }
    }

    func scanAgain() { phase = .scanning }
}

@MainActor
@Observable
final class MyQrViewModel {
    private(set) var qr: MyQr?
    private(set) var secondsLeft = 0
    private(set) var error: String?
    @ObservationIgnored private let context: AppContext
    private let renewBeforeSeconds = 30

    init(context: AppContext) { self.context = context }

    /// El servidor ya entrega el token con el prefijo "glasspay:"; se codifica tal cual, como en Android.
    var qrPayload: String? { qr?.token }

    /// Se renueva 30 s antes de vencer, solo mientras la pantalla está visible y la app desbloqueada.
    func run() async {
        while !Task.isCancelled {
            if qr == nil || secondsLeft <= renewBeforeSeconds {
                do {
                    qr = try await context.api.issueMyQr(context.session)
                    error = nil
                } catch {
                    guard !error.isCancellation else { return }
                    logFailure("myQr", error)
                    switch error.apiFailure {
                    case .unauthorized: return context.invalidate(sessionInvalidMessage)
                    case .rateLimited: self.error = "Demasiados intentos. Espera unos segundos."
                    case .network, .timeout:
                        // Si el QR actual sigue vigente, se muestra mientras se reintenta.
                        if qr.map({ remainingSeconds($0.expiresAt) }) ?? 0 <= 0 { self.error = "No pudimos generar tu QR." }
                    default: self.error = "No pudimos generar tu QR."
                    }
                    if self.error != nil { return }
                    try? await Task.sleep(for: .seconds(5))
                    continue
                }
            }
            secondsLeft = qr.map { remainingSeconds($0.expiresAt) } ?? 0
            try? await Task.sleep(for: .seconds(1))
            secondsLeft = qr.map { remainingSeconds($0.expiresAt) } ?? 0
        }
    }
}

@MainActor
@Observable
final class PayoutEmailViewModel {
    var email = ""
    private(set) var saving = false
    private(set) var message: String?
    @ObservationIgnored private let context: AppContext

    init(context: AppContext) { self.context = context }

    func save(onSaved: @escaping () -> Void) {
        guard isValidPayoutEmail(email) else {
            message = "Escribe un correo válido."
            return
        }
        guard !saving else { return }
        saving = true
        message = nil
        Task {
            defer { saving = false }
            do {
                _ = try await context.api.setPayoutEmail(context.session, email: email.trimmingCharacters(in: .whitespaces))
                onSaved()
            } catch {
                logFailure("payoutEmail", error)
                switch error.apiFailure {
                case .unauthorized: context.invalidate(sessionInvalidMessage)
                case .badRequest: message = "Ese correo no es válido."
                case .rateLimited: message = "Demasiados intentos. Espera unos segundos."
                default: message = "No pudimos guardar tu correo. Revisa tu conexión."
                }
            }
        }
    }
}
