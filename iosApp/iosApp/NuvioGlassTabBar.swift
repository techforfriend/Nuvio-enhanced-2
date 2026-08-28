import SwiftUI
import ComposeApp

enum NuvioTabBarBehavior: String, CaseIterable {
    case off
    case `static`
    case autoHide = "auto_hide"
    case morphed

    static let storageKey = "NuvioNativeTabBarBehavior"
    static let fallback: NuvioTabBarBehavior = .morphed

    static func current() -> NuvioTabBarBehavior {
        guard let raw = UserDefaults.standard.string(forKey: storageKey) else { return fallback }
        return NuvioTabBarBehavior(rawValue: raw) ?? fallback
    }

    var isEnabled: Bool { self != .off }

    var usesCompactPill: Bool { self == .morphed }

    var respondsToScroll: Bool { self == .autoHide || self == .morphed }
}

@available(iOS 26.0, *)
struct NuvioGlassTabBar: View {
    @ObservedObject var appCoordinator: AppNavigationCoordinator
    @ObservedObject var iconStore: NativeTabIconStore

    @Namespace private var glassNamespace
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    private static let barGlassID = "nuvio.tabbar"

    static let portraitBottomInset: CGFloat = 20
    static let landscapeBottomInset: CGFloat = 16

    private var bottomInset: CGFloat {
        verticalSizeClass == .compact ? Self.landscapeBottomInset : Self.portraitBottomInset
    }

    private var selectedTab: NuvioAppTab {
        appCoordinator.selectedTab
    }

    private var isExpanded: Bool {
        appCoordinator.isTabBarVisible
    }

    private var visibleTabs: [NuvioAppTab] {
        isExpanded ? appCoordinator.availableTabs : [selectedTab]
    }

    var body: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(visibleTabs, id: \.self) { tab in
                    item(for: tab)
                        .transition(.opacity)
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, isExpanded ? 3 : 5)
            .glassEffect(.clear.interactive(), in: Capsule())
            .glassEffectID(Self.barGlassID, in: glassNamespace)
        }
        .frame(maxWidth: .infinity, alignment: isExpanded ? .center : .leading)
        .padding(.horizontal, isExpanded ? 20 : 16)
        .padding(.bottom, bottomInset)
        .ignoresSafeArea(.container, edges: .bottom)
        // This pill is the only tab bar instrument in `morphed` — the real one stays hidden — so
        // it must stay tappable/accessible in both its expanded and collapsed shapes.
        .animation(.smooth(duration: 0.32), value: isExpanded)
        .animation(.smooth(duration: 0.22), value: selectedTab)
    }

    private func item(for tab: NuvioAppTab) -> some View {
        let selected = tab == selectedTab
        let content = Group {
            if verticalSizeClass == .compact {
                HStack(spacing: 6) {
                    icon(for: tab, selected: selected)
                    if isExpanded {
                        label(for: tab, selected: selected)
                    }
                }
            } else {
                VStack(spacing: 3) {
                    icon(for: tab, selected: selected)
                    if isExpanded {
                        label(for: tab, selected: selected)
                    }
                }
            }
        }
        .padding(.vertical, verticalSizeClass == .compact ? 6 : 7)
        .padding(.horizontal, verticalSizeClass == .compact ? 12 : (isExpanded ? 4 : 10))
        .frame(maxWidth: isExpanded && verticalSizeClass != .compact ? .infinity : nil)
        .contentShape(Capsule())
        .background {
            if selected && isExpanded {
                Capsule()
                    .fill(Color(uiColor: iconStore.accentColor).opacity(0.12))
            }
        }

        let button = Button {
            if selected {
                if isExpanded {
                    // Tapping the already-selected tab while expanded matches the real system tab
                    // bar's convention (scroll-to-top) instead of doing nothing.
                    NativeTabBridgeKt.nativeTabSelect(tabName: tab.rawValue)
                } else {
                    appCoordinator.requestTabBarVisible(true)
                }
            } else {
                appCoordinator.selectedTab = tab
            }
        } label: {
            content
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(appCoordinator.title(for: tab)))
        .accessibilityAddTraits(selected ? [.isSelected] : [])

        return Group {
            if tab == .settings {
                button.simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.45)
                        .onEnded { _ in
                            guard appCoordinator.isAppReady else { return }
                            appCoordinator.isProfileSwitcherPresented = true
                        }
                )
            } else {
                button
            }
        }
    }

    private func label(for tab: NuvioAppTab, selected: Bool) -> some View {
        Text(appCoordinator.title(for: tab))
            .font(.system(size: 11, weight: .medium))
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .foregroundStyle(
                selected ? AnyShapeStyle(Color(uiColor: iconStore.accentColor)) : AnyShapeStyle(Color.white)
            )
            .legibleOverGlass(enabled: !selected)
    }

    private func icon(for tab: NuvioAppTab, selected: Bool) -> some View {
        let image = Image(uiImage: iconStore.image(for: tab, selected: selected))

        return Group {
            if tab == .settings {
                image
                    .renderingMode(.original)
                    .resizable()
                    .scaledToFit()
            } else if selected {
                image
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(Color(uiColor: iconStore.accentColor))
            } else {
                image
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(Color.white)
            }
        }
        .frame(width: 24, height: 24)
        .legibleOverGlass(enabled: !selected)
    }
}

@available(iOS 26.0, *)
private extension View {
    func legibleOverGlass(enabled: Bool) -> some View {
        shadow(color: .black.opacity(enabled ? 0.35 : 0), radius: 2, x: 0, y: 0)
            .shadow(color: .black.opacity(enabled ? 0.22 : 0), radius: 5, x: 0, y: 1)
    }
}
