import SwiftUI

// Barra inferior propia (la del sistema no deja animar los íconos). Igual que en Android: el
// indicador se desliza hasta la pestaña elegida, su ícono rebota y el teléfono da un toque leve.

extension AppTab {
    var label: String {
        switch self {
        case .home: "Inicio"
        case .movements: "Movimientos"
        case .assistant: "Asistente"
        case .contacts: "Contactos"
        }
    }

    var icon: String {
        switch self {
        case .home: GPIcon.home
        case .movements: GPIcon.movements
        case .assistant: GPIcon.assistant
        case .contacts: GPIcon.contacts
        }
    }
}

struct GPTabBar: View {
    let selected: AppTab
    let onSelect: (AppTab) -> Void
    @Namespace private var indicator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                TabBarItem(tab: tab, selected: tab == selected, namespace: indicator) { onSelect(tab) }
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .background(GP.card.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(GP.line).frame(height: 1) }
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.8), value: selected)
        .sensoryFeedback(.selection, trigger: selected)
    }
}

private struct TabBarItem: View {
    let tab: AppTab
    let selected: Bool
    let namespace: Namespace.ID
    let action: () -> Void
    /// Solo cambia al quedar seleccionada: no rebota al abrir la app ni al deseleccionarse.
    @State private var bounce = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                ZStack {
                    if selected {
                        Capsule().fill(GP.accentSoft).matchedGeometryEffect(id: "indicator", in: namespace)
                    }
                    Image(systemName: tab.icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(selected ? GP.accent : GP.muted)
                        .symbolEffect(.bounce, value: bounce)
                }
                .frame(width: 64, height: 32)
                Text(tab.label)
                    .font(.system(size: 12, weight: selected ? .semibold : .medium))
                    .foregroundStyle(selected ? GP.text : GP.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onChange(of: selected) { _, isSelected in
            if isSelected && !reduceMotion { bounce += 1 }
        }
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
