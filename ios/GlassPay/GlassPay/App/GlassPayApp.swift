import MWDATCore
import SwiftUI

@main
struct GlassPayApp: App {
    @State private var app = AppViewModel()

    init() {
        // Debe registrarse antes de que termine el arranque de la app.
        ScheduledCheck.register(api: PaymentAPI())
        do { try Wearables.configure() } catch { logFailure("glasses configure", error) }
    }

    var body: some Scene {
        WindowGroup {
            RootView(app: app)
                .preferredColorScheme(.dark)
                // Respuesta de Meta AI al registro y a los permisos; cualquier otra URL se ignora.
                .onOpenURL { url in
                    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
                    guard items?.contains(where: { $0.name == "metaWearablesAction" }) == true else { return }
                    Task {
                        do { _ = try await Wearables.shared.handleUrl(url) } catch { logFailure("glasses callback", error) }
                    }
                }
        }
    }
}
