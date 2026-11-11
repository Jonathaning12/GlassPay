import SwiftUI

struct ContactsView: View {
    let app: AppViewModel
    @State private var model: ContactsViewModel

    init(app: AppViewModel, context: AppContext) {
        self.app = app
        _model = State(initialValue: ContactsViewModel(context: context))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(L("Contactos")).font(.gpTitle).foregroundStyle(GP.text)
                    Spacer()
                    Button { app.open(.addContact) } label: {
                        Image(systemName: GPIcon.plus).font(.system(size: 18, weight: .semibold)).foregroundStyle(GP.accent)
                            .frame(width: 44, height: 44).background(GP.card, in: Circle()).overlay(Circle().stroke(GP.line))
                    }
                    .accessibilityLabel(L("Agregar con QR"))
                }
                if let message = model.message { InlineNotice(message, kind: .pending) }
                switch model.state {
                case .loading:
                    ForEach(0..<4, id: \.self) { _ in SkeletonBox(height: 72) }
                case .failed:
                    InlineNotice(L("No pudimos cargar tus contactos."), kind: .failure)
                    GPButton(L("Reintentar"), kind: .secondary) { Task { await model.reload() } }
                case let .loaded(contacts):
                    if contacts.isEmpty {
                        GPCard {
                            IconBadge(icon: GPIcon.contacts, size: 56)
                            Text(L("Aún no tienes contactos")).font(.gpBodyStrong).foregroundStyle(GP.text)
                            Text(L("Escanea el QR de un amigo y guárdalo para cobrarle más rápido.")).font(.gpNote).foregroundStyle(GP.muted)
                            GPButton(L("Agregar con QR"), icon: GPIcon.qr) { app.open(.addContact) }
                        }
                    }
                    ForEach(contacts) { row($0) }
                    if !contacts.isEmpty { GPButton(L("Actualizar"), kind: .ghost) { Task { await model.reload() } }.frame(maxWidth: .infinity) }
                }
            }
            .padding(20)
        }
        .refreshable { await model.reload() }
        .gpScreen()
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.reload() }
        .confirmationDialog(
            model.pendingDelete.map { L("¿Eliminar a %@ de tus contactos?", "\($0.displayName)") } ?? "",
            isPresented: Binding(get: { model.pendingDelete != nil }, set: { if !$0 { model.pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button(L("Eliminar"), role: .destructive) {
                if let contact = model.pendingDelete { model.delete(contact) }
                model.pendingDelete = nil
            }
            Button(L("Cancelar"), role: .cancel) { model.pendingDelete = nil }
        }
    }

    private func row(_ contact: Contact) -> some View {
        GPCard(padding: 16, spacing: 12) {
            HStack(spacing: 12) {
                AvatarView(name: contact.displayName)
                Text(contact.displayName).font(.gpBodyStrong).foregroundStyle(GP.text)
                Spacer()
                Button { model.pendingDelete = contact } label: {
                    Image(systemName: GPIcon.trash).foregroundStyle(GP.error).frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("Eliminar a %@", "\(contact.displayName)"))
            }
            HStack(spacing: 12) {
                GPButton(L("Cobrar"), icon: GPIcon.charge) { model.charge(contact, onResolved: app.chargeContact) }
                    .disabled(model.busy)
                GPButton(L("Enviar"), icon: GPIcon.send, kind: .secondary) { app.open(.send(contact)) }
            }
        }
    }
}

struct AddContactView: View {
    @State private var model: AddContactViewModel
    @Environment(\.dismiss) private var dismiss

    init(context: AppContext) { _model = State(initialValue: AddContactViewModel(context: context)) }

    var body: some View {
        ZStack {
            if model.cameraGranted, model.phase == .scanning {
                QRScannerView(onToken: model.handle).ignoresSafeArea()
                GP.background.opacity(0.35).ignoresSafeArea()
                ScanFrame().stroke(GP.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round)).frame(width: 264, height: 264)
            }
            VStack {
                Spacer()
                VStack(alignment: .leading, spacing: 12) {
                    switch model.phase {
                    case .scanning:
                        Text(L("Agregar contacto")).font(.gpBodyStrong).foregroundStyle(GP.text)
                        Text(L("Apunta al QR de tu amigo. Solo se guarda su nombre visible.")).font(.gpNote).foregroundStyle(GP.muted)
                        if !model.cameraGranted {
                            Text(L("Necesitamos la cámara para leer el QR.")).font(.gpLabel).foregroundStyle(GP.error)
                            GPButton(L("Permitir cámara")) { Task { await model.requestCamera() } }
                        }
                    case .adding:
                        WorkingRow(text: L("Agregando contacto…"))
                    case let .done(message, isError):
                        InlineNotice(message, kind: isError ? .failure : .success)
                        GPButton(L("Listo")) { dismiss() }
                        GPButton(L("Agregar otro"), kind: .secondary) { model.scanAgain() }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GP.card.opacity(0.94), in: RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous).stroke(GP.line))
                .padding(20)
            }
        }
        .gpScreen()
        .navigationTitle(L("Agregar con QR"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.requestCamera() }
    }
}

struct MyQrView: View {
    let locked: Bool
    @State private var model: MyQrViewModel
    @State private var previousBrightness: CGFloat?
    private let qrBrightness: CGFloat = 0.7
    @Environment(\.scenePhase) private var scenePhase

    init(context: AppContext, locked: Bool) {
        self.locked = locked
        _model = State(initialValue: MyQrViewModel(context: context))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(L("Muéstralo para que te agreguen o te cobren.")).font(.gpBody).foregroundStyle(GP.muted).multilineTextAlignment(.center)
                if let payload = model.qrPayload, let image = QRCodeImage.make(payload) {
                    VStack(spacing: 12) {
                        Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 248, height: 248)
                            .accessibilityLabel(L("Tu código QR de GlassPay"))
                        Text(L("Sin datos personales en el código")).font(.gpNote).foregroundStyle(Color(hex: 0x5B6475))
                    }
                    .padding(20)
                    .background(GP.qrPaper, in: RoundedRectangle(cornerRadius: GP.radiusLarge, style: .continuous))
                    HStack(spacing: 8) {
                        ProgressView(value: min(1, Double(model.secondsLeft) / 300)).tint(GP.accent).frame(width: 64)
                        Text(L("Se renueva en %@ s", "\(model.secondsLeft)")).font(.gpLabel).foregroundStyle(GP.text)
                    }
                } else if model.error == nil {
                    ProgressView().tint(GP.accent).padding(.top, 48).accessibilityLabel(L("Generando tu QR"))
                }
                if let error = model.error {
                    InlineNotice(error, kind: .failure)
                    GPButton(L("Reintentar")) { Task { await model.run() } }
                }
            }
            .padding(20)
        }
        .gpScreen()
        .navigationTitle(L("Mi QR"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: !locked && scenePhase == .active) {
            if !locked && scenePhase == .active { await model.run() }
        }
        .onAppear {
            // Brillo medio: al máximo la pantalla se satura y los lentes leen peor el QR. No se vuelve a
            // imponer, así que el usuario puede ajustarlo; solo se restaura si no lo cambió.
            previousBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = qrBrightness
        }
        .onDisappear {
            if let previousBrightness, abs(UIScreen.main.brightness - qrBrightness) < 0.01 {
                UIScreen.main.brightness = previousBrightness
            }
        }
    }
}

struct PayoutEmailView: View {
    @State private var model: PayoutEmailViewModel
    @Environment(\.dismiss) private var dismiss

    init(context: AppContext) { _model = State(initialValue: PayoutEmailViewModel(context: context)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L("Escribe el correo de tu cuenta de PayPal Sandbox.")).font(.gpBody).foregroundStyle(GP.text)
                InlineNotice(L("Tu correo se guarda solo en el servidor de GlassPay y nadie más lo ve."), icon: GPIcon.lock)
                TextField(L("Correo de PayPal Sandbox"), text: $model.email)
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .gpField()
                    .disabled(model.saving)
                if let message = model.message { InlineNotice(message, kind: .failure) }
                GPButton(model.saving ? L("Guardando…") : L("Guardar")) { model.save { dismiss() } }
                    .disabled(model.email.trimmingCharacters(in: .whitespaces).isEmpty || model.saving)
            }
            .padding(20)
        }
        .gpScreen()
        .navigationTitle(L("Recibir envíos"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
