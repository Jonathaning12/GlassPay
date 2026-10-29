import SwiftUI
import UIKit

// GlassPayRoot de Android: bienvenida o pestañas, banner sin conexión, bloqueo y capa de privacidad.

struct RootView: View {
    @Bindable var app: AppViewModel
    @State private var network = NetworkMonitor()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                if !network.online { OfflineBanner() }
                content
            }
            .accessibilityHidden(app.lockActive)

            if app.lockActive {
                LockView { Task { await app.unlock() } }
                    .transition(.opacity)
            }
            // Equivalente a ocultar la miniatura de Recientes: en el selector de apps no se ven montos.
            if scenePhase != .active && app.session != nil {
                PrivacyCover()
            }
        }
        .background(GP.background.ignoresSafeArea())
        .task { await app.start() }
        .task(id: app.lockActive) { if app.lockActive { await app.unlock() } }
        .onChange(of: scenePhase) { _, phase in app.scenePhaseChanged(phase) }
    }

    @ViewBuilder
    private var content: some View {
        if !app.loaded {
            ProgressView().tint(GP.accent).frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let context = app.context {
            MainTabs(app: app, context: context)
        } else {
            WelcomeView(api: app.api, message: app.welcomeMessage, onCreated: app.accountCreated)
        }
    }
}

private struct MainTabs: View {
    @Bindable var app: AppViewModel
    let context: AppContext
    @State private var keyboardVisible = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                TabView(selection: app.tabBinding) {
                    NavigationStack(path: $app.homePath) {
                        HomeView(app: app, context: context)
                            .navigationDestination(for: Route.self) { destination($0) }
                    }
                    .toolbar(.hidden, for: .tabBar)
                    .tag(AppTab.home)

                    NavigationStack {
                        MovementsView(context: context, amountsVisible: $app.amountsVisible)
                    }
                    .toolbar(.hidden, for: .tabBar)
                    .tag(AppTab.movements)

                    NavigationStack(path: $app.assistantPath) {
                        Group {
                            if let assistant = app.assistant {
                                AssistantView(model: assistant, onOpenScheduled: { app.open(.scheduled) })
                            } else {
                                Color.clear
                            }
                        }
                        .navigationDestination(for: Route.self) { destination($0) }
                    }
                    .toolbar(.hidden, for: .tabBar)
                    .tag(AppTab.assistant)

                    NavigationStack(path: $app.contactsPath) {
                        ContactsView(app: app, context: context)
                            .navigationDestination(for: Route.self) { destination($0) }
                    }
                    .toolbar(.hidden, for: .tabBar)
                    .tag(AppTab.contacts)
                }
                .tint(GP.accent)
                // Como la barra del sistema, la propia queda oculta mientras se escribe.
                if !keyboardVisible {
                    GPTabBar(selected: app.tab, onSelect: app.selectTab)
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if let notice = app.saveNotice {
                    Text(notice).font(.gpNote).foregroundStyle(GP.warning).padding(12).frame(maxWidth: .infinity)
                }
            }

            // Cobrar va en pantalla completa sobre las pestañas (sin barra), pero debajo del bloqueo.
            if let charge = app.charge {
                NavigationStack {
                    ChargeView(model: charge)
                        .navigationTitle("Cobrar")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(GP.background, for: .navigationBar)
                        .toolbarBackground(.visible, for: .navigationBar)
                        .toolbar {
                            if app.canLeaveCharge {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button { app.closeCharge() } label: { Image(systemName: "chevron.left") }
                                        .accessibilityLabel("Volver")
                                }
                            }
                        }
                }
                .tint(GP.accent)
                .transition(.move(edge: .bottom))
                .zIndex(1)
            }
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: app.charge != nil)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardVisible = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardVisible = false }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .profile: ProfileView(app: app, context: context)
        case .myQr: MyQrView(context: context, locked: app.lockActive)
        case .stats: StatsView(context: context)
        case .scheduled: ScheduledView(context: context)
        case .payoutEmail: PayoutEmailView(context: context)
        case .addContact: AddContactView(context: context)
        case let .send(contact):
            SendMoneyView(context: context, initialContact: contact, locked: app.lockActive, onOpenMovements: { app.selectTab(.movements) })
        }
    }
}

private struct OfflineBanner: View {
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: GPIcon.wifiOff)
            Text("Sin conexión. Revisa tu red.").font(.gpLabel)
        }
        .foregroundStyle(GP.background)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(GP.warning)
        .accessibilityElement(children: .combine)
    }
}

private struct LockView: View {
    let onUnlock: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            IconBadge(icon: GPIcon.lock, size: 88)
            Text("GlassPay").font(.system(size: 32, weight: .semibold)).foregroundStyle(GP.text)
            Text("Está bloqueada. Usa Face ID o el código de tu teléfono.")
                .font(.gpBody).foregroundStyle(GP.muted).multilineTextAlignment(.center)
            GPButton("Desbloquear", icon: GPIcon.lock, action: onUnlock)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GP.background.ignoresSafeArea())
        .contentShape(Rectangle())
    }
}

private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            GP.background.ignoresSafeArea()
            Image("BrandMark").resizable().scaledToFit().frame(width: 96, height: 96).accessibilityHidden(true)
        }
    }
}
