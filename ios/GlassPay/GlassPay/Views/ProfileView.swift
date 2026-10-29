import SwiftUI

// Perfil del cobrador (ProfileScreen.kt): accesos a Cobrar, Enviar y Mi QR, ajustes de la cuenta y
// seguridad. Solo muestra datos que el servidor ya entrega (nombre y correo para recibir envíos).

struct ProfileView: View {
    @Bindable var app: AppViewModel
    let context: AppContext
    @State private var profile: LoadState<CollectorProfile> = .loading
    @State private var revealed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(spacing: 6) {
                    AvatarView(name: context.session.displayName, size: 80, highlighted: true)
                    Text(context.session.displayName)
                        .font(.gpTitle).foregroundStyle(GP.text)
                        .multilineTextAlignment(.center).lineLimit(2)
                        .padding(.top, 6)
                    Text("Cuenta de cobrador · Sandbox").font(.gpNote).foregroundStyle(GP.muted)
                }
                .frame(maxWidth: .infinity)
                .reveal(revealed, index: 0)

                section("Mover dinero") {
                    ProfileRow(icon: GPIcon.charge, title: "Cobrar", description: "Escanea el QR de un amigo y dicta el cobro", highlighted: true) {
                        app.openCharge()
                    }
                    rowDivider
                    ProfileRow(icon: GPIcon.send, title: "Enviar", description: "Envía dinero a uno de tus contactos") { app.open(.send(nil)) }
                    rowDivider
                    ProfileRow(icon: GPIcon.qr, title: "Mi QR", description: "Muéstralo para que te cobren o te agreguen") { app.open(.myQr) }
                }
                .reveal(revealed, index: 1)

                section("Cuenta") {
                    ProfileRow(icon: GPIcon.mail, title: "Recibir envíos", description: payoutDescription) { app.open(.payoutEmail) }
                    rowDivider
                    ProfileRow(icon: GPIcon.clock, title: "Programados", description: "Cobros y pagos que programaste con el asistente") {
                        app.open(.scheduled)
                    }
                }
                .reveal(revealed, index: 2)

                section("Seguridad y privacidad") {
                    Toggle(isOn: $app.amountsVisible) {
                        HStack(spacing: 16) {
                            EyeGlyph(open: app.amountsVisible)
                                .frame(width: 40, height: 40)
                                .background(GP.raised, in: Circle())
                            rowTexts("Mostrar montos", "Se ocultan de nuevo al salir de la app")
                        }
                    }
                    .tint(GP.accent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .frame(minHeight: 72)
                    rowDivider
                    lockRow
                }
                .reveal(revealed, index: 3)

                InlineNotice("Actividad en GlassPay (Sandbox). No es una cuenta bancaria.", icon: GPIcon.lock)
                    .reveal(revealed, index: 4)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .gpScreen()
        .navigationTitle("Mi perfil")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { revealed = true }
        // Se vuelve a cargar cada vez que se entra (por ejemplo, al volver de "Recibir envíos").
        .task { await load() }
    }

    private var payoutDescription: String {
        switch profile {
        case .loading: "Cargando…"
        case .failed: "Correo de PayPal Sandbox para recibir envíos"
        case let .loaded(value): value.payoutEmail.map { "Recibes en \($0)" } ?? "Registra tu correo de PayPal Sandbox"
        }
    }

    /// Fila informativa (sin acción): no tiene chevron ni efecto al tocar.
    private var lockRow: some View {
        let lockOn = app.authAvailability == .available
        let kind: StatusKind = lockOn ? .success : .pending
        return HStack(spacing: 16) {
            IconBadge(icon: GPIcon.lock, tint: GP.status(kind), background: GP.statusSoft(kind))
            rowTexts(
                "Bloqueo con tu teléfono",
                lockOn
                    ? "Se pide al abrir, tras 5 min fuera y antes de enviar dinero"
                    : "Configura un código en tu teléfono para proteger GlassPay"
            )
            Spacer(minLength: 0)
            StatusPill(text: lockOn ? "Activo" : "Inactivo", kind: kind)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(minHeight: 72)
        .accessibilityElement(children: .combine)
    }

    private var rowDivider: some View {
        Divider().overlay(GP.line).padding(.leading, 72)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Overline(title).padding(.leading, 4)
            VStack(spacing: 0) { content() }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GP.card, in: RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous).stroke(GP.line, lineWidth: 1))
        }
    }

    private func load() async {
        do {
            profile = .loaded(try await context.api.collectorProfile(context.session))
        } catch {
            guard !error.isCancellation else { return }
            logFailure("profile", error)
            if error.apiFailure == .unauthorized { return context.invalidate(sessionInvalidMessage) }
            profile = .failed
        }
    }
}

private struct ProfileRow: View {
    let icon: String
    let title: String
    let description: String
    var highlighted = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                IconBadge(icon: icon, background: highlighted ? GP.accentSoft : GP.raised)
                rowTexts(title, description)
                Spacer(minLength: 0)
                Image(systemName: GPIcon.chevron).font(.system(size: 14, weight: .semibold)).foregroundStyle(GP.muted)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(minHeight: 72)
        }
        .buttonStyle(PressScaleStyle())
    }
}

private func rowTexts(_ title: String, _ description: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(title).font(.gpBodyStrong).foregroundStyle(GP.text)
        Text(description).font(.gpNote).foregroundStyle(GP.muted)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .multilineTextAlignment(.leading)
}

private extension View {
    /// Entrada escalonada y discreta: aparece y sube 12 pt en 220 ms.
    func reveal(_ visible: Bool, index: Int) -> some View {
        modifier(RevealModifier(visible: visible, index: index))
    }
}

private struct RevealModifier: ViewModifier {
    let visible: Bool
    let index: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let shown = visible || reduceMotion
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 12)
            .animation(reduceMotion ? nil : .timingCurve(0.4, 0, 0.2, 1, duration: 0.22).delay(Double(index) * 0.04), value: visible)
    }
}
