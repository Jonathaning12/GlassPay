import SwiftUI

@main
struct GlassPayApp: App {
    @State private var app = AppViewModel()

    init() {
        // Debe registrarse antes de que termine el arranque de la app.
        ScheduledCheck.register(api: PaymentAPI())
    }

    var body: some Scene {
        WindowGroup {
            RootView(app: app)
                .preferredColorScheme(.dark)
        }
    }
}
