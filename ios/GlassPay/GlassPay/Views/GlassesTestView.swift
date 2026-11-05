import MWDATCore
import SwiftUI

// Pantalla temporal de la prueba de viabilidad con los lentes. No cobra ni llama al servidor.

struct GlassesTestView: View {
    @State private var model = GlassesCamera()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                InlineNotice("Prueba de lectura: no se cobra nada. Solo se guardan imágenes si pulsas Guardar diagnóstico.", icon: GPIcon.lock)
                statusCard
                if let error = model.errorMessage {
                    InlineNotice(error, kind: .failure)
                }
                if model.isRegistered {
                    videoCard
                    if model.hasSession { measureCard }
                } else {
                    GPButton("Conectar lentes") { Task { await model.connect() } }
                        .disabled(model.registration == .registering)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .gpScreen()
        .navigationTitle("Probar lentes")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { model.tearDown() }
        .alert("Autorizar en Meta AI", isPresented: $model.showPermissionPrompt) {
            Button("Abrir Meta AI") { Task { await model.grantPermissionAndStream() } }
            Button("Cancelar", role: .cancel) { model.stop() }
        } message: {
            Text("Se abrirá Meta AI para permitir que GlassPay use la cámara de los lentes.")
        }
    }

    private var statusCard: some View {
        GPCard(spacing: 8) {
            statusRow("Registro", String(describing: model.registration))
            statusRow("Lentes activos", model.hasActiveDevice ? "Sí" : "No: póntelos y revisa la conexión")
            statusRow("Estado", model.deviceStatus)
            statusRow("Autoprueba del lector", model.readerSelfTest)
            statusRow("Sesión", String(describing: model.sessionState))
            statusRow("Video", String(describing: model.streamState))
        }
    }

    private var videoCard: some View {
        GPCard(spacing: 12) {
            Picker("Resolución", selection: $model.resolution) {
                ForEach(GlassesResolution.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(model.hasSession)
            Picker("Cuadros por segundo", selection: $model.frameRate) {
                ForEach([UInt(2), 7, 15, 24], id: \.self) { Text("\($0) fps").tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(model.hasSession)

            if let preview = model.preview {
                Image(uiImage: preview)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 360)
                    .clipShape(RoundedRectangle(cornerRadius: GP.radiusMedium, style: .continuous))
                    .accessibilityLabel("Video de los lentes")
            }

            if model.hasSession {
                GPButton("Detener video", kind: .secondary) { model.stop() }.disabled(model.isBusy)
            } else {
                GPButton("Iniciar video") { model.start() }.disabled(!model.hasActiveDevice)
                GPButton("Desconectar lentes", kind: .ghost) { Task { await model.disconnect() } }
            }
        }
        .disabled(model.isBusy && !model.hasSession)
    }

    private var measureCard: some View {
        GPCard(spacing: 10) {
            Text("Mira el QR de GlassPay y pulsa Medir").font(.gpBodyStrong).foregroundStyle(GP.text)
            GPButton(model.measuring ? "Buscando QR…" : "Medir", icon: GPIcon.qr) { model.startMeasure() }
                .disabled(!model.isStreaming || model.measuring)
            statusRow("Lecturas", model.reads.isEmpty ? "Ninguna" : model.reads.map { String(format: "%.1f s", $0) }.joined(separator: " · "))
            statusRow("Cuadros", "\(model.framesReceived) recibidos de \(model.frameSize), \(model.framesAnalyzed) analizados")
            statusRow("QR vistos", "\(model.framesWithCode) cuadros" + (model.lastCodeSize.map { ", último de \($0) px" } ?? ""))
            statusRow("Otros QR ignorados", "\(model.ignoredCodes)")
            GPButton("Probar con foto", kind: .secondary) { model.capturePhoto() }.disabled(!model.isStreaming)
            if let result = model.photoResult { statusRow("Foto", result) }
            GPButton("Guardar diagnóstico", kind: .secondary) { model.saveDiagnostic() }.disabled(!model.isStreaming)
            if let message = model.diagnosticMessage { statusRow("Diagnóstico", message) }
            GPButton("Borrar mediciones", kind: .ghost) { model.resetMeasures() }
        }
    }

    private func statusRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).foregroundStyle(GP.muted)
            Spacer(minLength: 12)
            Text(value).foregroundStyle(GP.text).multilineTextAlignment(.trailing)
        }
        .font(.gpNote)
        .accessibilityElement(children: .combine)
    }
}
