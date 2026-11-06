import MWDATCore
import SwiftUI

// Conecta GlassPay con los lentes por medio de Meta AI. El cobro con los lentes se hace en Cobrar.

struct GlassesSetupView: View {
    @State private var model = GlassesCamera()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                GPCard(spacing: 12) {
                    Text(model.isRegistered ? "Lentes conectados" : "Conecta tus lentes")
                        .font(.gpHeading).foregroundStyle(GP.text)
                    Text(detail).font(.gpBody).foregroundStyle(GP.muted)
                }
                if let error = model.errorMessage { InlineNotice(error, kind: .failure) }
                if model.isRegistered {
                    GPButton("Desconectar lentes", kind: .secondary) { Task { await model.disconnect() } }
                } else {
                    GPButton("Conectar lentes", icon: "eyeglasses") { Task { await model.connect() } }
                        .disabled(model.registration == .registering)
                }
                InlineNotice("El video de los lentes solo se usa para leer el QR y no se guarda.", icon: GPIcon.lock)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .gpScreen()
        .navigationTitle("Lentes")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { model.tearDown() }
    }

    private var detail: String {
        guard model.isRegistered else { return "Se abrirá Meta AI para autorizar GlassPay. Después podrás cobrar mirando el QR de tu amigo." }
        return model.hasActiveDevice ? "Abre Cobrar y mira el QR de tu amigo." : "Póntelos y revisa que estén enlazados para usarlos en Cobrar."
    }
}
