import SwiftUI

struct WelcomeView: View {
    let api: PaymentAPI
    let message: String?
    let onCreated: (CollectorSession) -> Void
    @State private var model = WelcomeViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image("BrandMark").resizable().scaledToFit().frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .accessibilityHidden(true)
                Text(verbatim: "GlassPay").font(.system(size: 40, weight: .bold)).foregroundStyle(GP.text)
                Text(L("Cobra a tus amigos con la voz y un QR.")).font(.gpBody).foregroundStyle(GP.muted)
                if let message { InlineNotice(message, kind: .pending) }
                VStack(alignment: .leading, spacing: 6) {
                    TextField(L("Tu nombre"), text: $model.name)
                        .textContentType(.givenName)
                        .submitLabel(.done)
                        .onSubmit { model.createAccount(api: api, onCreated: onCreated) }
                        .gpField()
                        .disabled(model.creating)
                    Text(L("Es lo que verán tus amigos al cobrarles.")).font(.gpNote).foregroundStyle(GP.muted)
                }
                GPButton(model.creating ? L("Creando tu cuenta…") : L("Crear mi cuenta")) {
                    model.createAccount(api: api, onCreated: onCreated)
                }
                .disabled(model.name.trimmingCharacters(in: .whitespaces).isEmpty || model.creating)
                if model.waking { InlineNotice(L("Despertando el servidor… la primera vez tarda unos segundos."), kind: .pending) }
                if let error = model.error { InlineNotice(error, kind: .failure) }
                Text(L("Actividad en GlassPay (Sandbox): no se mueve dinero real.")).font(.gpNote).foregroundStyle(GP.muted)
            }
            .padding(24)
            .padding(.top, 40)
        }
        .gpScreen()
    }
}

struct HomeView: View {
    let app: AppViewModel
    @State private var model: HomeViewModel
    @Environment(\.scenePhase) private var scenePhase

    init(app: AppViewModel, context: AppContext) {
        self.app = app
        _model = State(initialValue: HomeViewModel(context: context))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                if app.authAvailability != .available {
                    InlineNotice(L("Configura un código en tu teléfono para proteger GlassPay."), kind: .pending, icon: GPIcon.lock)
                }
                balanceCard
                if model.awaitingConfirmation > 0 { awaitingCard }
                remindersSection
                incomingSection
                if model.profile.value != nil {
                    GPButton(L("Actualizar"), kind: .ghost) { Task { await model.reload() } }.frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .refreshable { await model.reload() }
        .gpScreen()
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.reload() }
        // "Por pagar": solo con Inicio visible, la app en primer plano y desbloqueada.
        .task(id: !app.lockActive && scenePhase == .active) {
            if !app.lockActive && scenePhase == .active { await model.pollIncoming() }
        }
        .confirmationDialog(
            rejectTitle,
            isPresented: Binding(get: { model.pendingReject != nil }, set: { if !$0 { model.pendingReject = nil } }),
            titleVisibility: .visible
        ) {
            Button(L("Rechazar"), role: .destructive) {
                if let request = model.pendingReject { model.reject(request) }
                model.pendingReject = nil
            }
            Button(L("Cancelar"), role: .cancel) { model.pendingReject = nil }
        }
    }

    private var rejectTitle: String {
        guard let request = model.pendingReject else { return "" }
        return L("¿Rechazar el cobro de %@ por %@?", request.fromName ?? L("un contacto"), formatMoney(request.amount, request.currency))
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("Hola, %@", app.session?.displayName ?? "")).font(.gpTitle).foregroundStyle(GP.text)
                Text(L("Actividad en GlassPay (Sandbox)")).font(.gpNote).foregroundStyle(GP.muted)
            }
            Spacer()
            // El avatar abre el perfil (Cobrar, Enviar, Mi QR, Recibir envíos, Programados, seguridad).
            Button { app.open(.profile) } label: {
                AvatarView(name: app.session?.displayName ?? "", size: 44, highlighted: true).frame(width: 48, height: 48)
            }
            .buttonStyle(PressScaleStyle())
            .accessibilityLabel(L("Abrir mi perfil"))
        }
    }

    private var balanceCard: some View {
        GPCard(padding: 24, spacing: 20) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Overline(L("Saldo disponible"))
                    switch model.profile {
                    case .loading: SkeletonBox(height: 48).frame(maxWidth: 220)
                    case .failed: Text(L("No pudimos cargar tu actividad.")).foregroundStyle(GP.error)
                    case let .loaded(profile):
                        if profile.activity.isEmpty { Text(L("Aún no has cobrado. Empieza con Cobrar.")).foregroundStyle(GP.muted) }
                        ForEach(profile.activity) { item in
                            MaskedMoney(formatted: formatMoney(item.availableTotal, item.currency), visible: app.amountsVisible)
                        }
                    }
                }
                Spacer()
                EyeToggle(visible: app.amountsVisible) { app.amountsVisible.toggle() }
            }
            switch model.profile {
            case .loading:
                Divider().overlay(GP.line)
                HStack(spacing: 12) { SkeletonBox(height: 40); SkeletonBox(height: 40) }
            case .failed:
                GPButton(L("Reintentar"), kind: .secondary) { Task { await model.reload() } }
            case let .loaded(profile):
                ForEach(profile.activity) { item in
                    Divider().overlay(GP.line)
                    HStack(spacing: 12) {
                        totalItem(L("Cobrado"), formatMoney(item.paidTotal, item.currency), GPIcon.receive, GP.success)
                        totalItem(L("Enviado"), formatMoney(item.sentTotal, item.currency), GPIcon.send, GP.text)
                    }
                    HStack(spacing: 8) {
                        Text(L("%@ pagos recibidos", "\(item.paidCount)")).font(.gpNote).foregroundStyle(GP.muted)
                        if item.inProgressCount > 0 { StatusPill(text: L("%@ en proceso", "\(item.inProgressCount)"), kind: .pending) }
                    }
                }
            }
            // Único acceso directo que queda en Inicio: cobrar es el flujo central de la demo.
            GPButton(L("Cobrar"), icon: GPIcon.charge) { app.openCharge() }
            if model.profile.value != nil {
                Divider().overlay(GP.line)
                Button { app.open(.stats) } label: {
                    HStack(spacing: 8) {
                        Image(systemName: GPIcon.chart)
                        Text(L("Ver estadísticas")).font(.gpLabel)
                        Spacer()
                        Image(systemName: GPIcon.chevron)
                    }
                    .foregroundStyle(GP.accent)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
            }
        }
    }

    private func totalItem(_ label: String, _ value: String, _ icon: String, _ tint: Color) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(tint)
            VStack(alignment: .leading) {
                Text(label).font(.gpNote).foregroundStyle(GP.muted)
                MaskedMoney(formatted: value, visible: app.amountsVisible, font: .gpBodyStrong, large: false)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var awaitingCard: some View {
        GPCard(spacing: 12) {
            HStack(spacing: 12) {
                IconBadge(icon: GPIcon.clock, tint: GP.warning, background: GP.warningSoft)
                Text(model.awaitingConfirmation == 1
                     ? L("1 cobro programado espera tu confirmación")
                     : L("%@ cobros programados esperan tu confirmación", "\(model.awaitingConfirmation)"))
                    .font(.gpBodyStrong).foregroundStyle(GP.text)
            }
            GPButton(L("Revisar")) { app.open(.scheduled) }
        }
    }

    @ViewBuilder
    private var remindersSection: some View {
        if !model.reminders.isEmpty {
            Text(L("Recordatorios")).font(.gpHeading).foregroundStyle(GP.text)
            ForEach(model.reminders) { reminder in
                GPCard(spacing: 12) {
                    HStack(spacing: 12) {
                        IconBadge(icon: GPIcon.clock, tint: GP.warning, background: GP.warningSoft)
                        Text(reminder.text).font(.gpBody).foregroundStyle(GP.text)
                    }
                    GPButton(L("Descartar"), kind: .ghost) { model.dismiss(reminder) }
                }
            }
        }
    }

    @ViewBuilder
    private var incomingSection: some View {
        let visible = model.visibleIncoming
        if !visible.isEmpty {
            HStack {
                Text(L("Por pagar")).font(.gpHeading).foregroundStyle(GP.text)
                Spacer()
                Text(visible.count == 1 ? L("1 solicitud") : L("%@ solicitudes", "\(visible.count)")).font(.gpNote).foregroundStyle(GP.muted)
            }
            ForEach(visible) { request in
                GPCard {
                    HStack(spacing: 12) {
                        AvatarView(name: request.fromName ?? "?")
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("Te cobra %@", request.fromName ?? L("un contacto"))).font(.gpBodyStrong).foregroundStyle(GP.text)
                            Text(request.concept).font(.gpNote).foregroundStyle(GP.muted)
                        }
                        Spacer()
                        Text(withoutCurrency(formatMoney(request.amount, request.currency))).font(.gpHeading).foregroundStyle(GP.text)
                    }
                    if request.state == "aprobada" {
                        StatusPill(text: L("Procesando"), kind: .pending)
                    } else {
                        GPButton(L("Aprobar con PayPal"), icon: GPIcon.check) { model.approve(request) }
                        GPButton(L("Rechazar"), kind: .secondary) { model.pendingReject = request }.disabled(model.rejecting)
                    }
                }
            }
            if let message = model.incomingMessage { InlineNotice(message, kind: .pending) }
        }
    }
}
