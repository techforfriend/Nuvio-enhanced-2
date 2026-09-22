import SwiftUI
import UIKit

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

struct NuvioTabBarItemMetrics: Equatable {
    var buttonFrame: CGRect
    var iconFrame: CGRect
    var labelFrame: CGRect?
    var labelFont: UIFont?
}

struct NuvioTabBarMetrics: Equatable {
    var windowSize: CGSize
    var leadingInset: CGFloat
    var trailingInset: CGFloat
    var bottomInset: CGFloat
    var height: CGFloat
    var items: [NuvioTabBarItemMetrics] = []
    var selectionFrame: CGRect? = nil
    var selectionItemIndex: Int? = nil

    func selectionFrame(forItemAt index: Int) -> CGRect? {
        guard let selectionFrame,
              let measuredIndex = selectionItemIndex,
              items.indices.contains(measuredIndex),
              items.indices.contains(index) else { return nil }
        let dx = items[index].buttonFrame.midX - items[measuredIndex].buttonFrame.midX
        return selectionFrame.offsetBy(dx: dx, dy: 0)
    }

    var width: CGFloat { windowSize.width - leadingInset - trailingInset }

    static func key(for size: CGSize) -> String {
        "\(Int(size.width.rounded()))x\(Int(size.height.rounded()))"
    }
}

@available(iOS 26.0, *)
struct NuvioGlassTabBar: View {
    @ObservedObject var appCoordinator: AppNavigationCoordinator
    @ObservedObject var iconStore: NativeTabIconStore
    var expandedMetrics: NuvioTabBarMetrics? = nil

    @Namespace private var glassNamespace
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    private static let barGlassID = "nuvio.tabbar"

    static let portraitBottomInset: CGFloat = 20
    static let landscapeBottomInset: CGFloat = 16

    private var bottomInset: CGFloat {
        if isExpanded, let expandedMetrics { return expandedMetrics.bottomInset }
        return verticalSizeClass == .compact ? Self.landscapeBottomInset : Self.portraitBottomInset
    }

    private var expandedHeight: CGFloat? {
        isExpanded ? expandedMetrics?.height : nil
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

    private var mirroredItems: [NuvioTabBarItemMetrics]? {
        guard isExpanded,
              let items = expandedMetrics?.items,
              !items.isEmpty,
              items.count == visibleTabs.count else { return nil }
        return items
    }

    var body: some View {
        GlassEffectContainer(spacing: 0) {
            Group {
                if let mirroredItems, let expandedMetrics {
                    mirroredContent(items: mirroredItems, metrics: expandedMetrics)
                } else {
                    HStack(spacing: 0) {
                        ForEach(visibleTabs, id: \.self) { tab in
                            item(for: tab)
                                .transition(.opacity)
                        }
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, expandedHeight != nil ? 0 : (isExpanded ? 3 : 5))
                    .frame(height: expandedHeight)
                }
            }
            .glassEffect(.clear.interactive(), in: Capsule())
            .glassEffectID(Self.barGlassID, in: glassNamespace)
        }
        .frame(maxWidth: .infinity, alignment: isExpanded ? .center : .leading)
        .padding(.bottom, bottomInset)
        .ignoresSafeArea(.container, edges: .bottom)
        .allowsHitTesting(!isExpanded)
        .accessibilityHidden(isExpanded)
        .animation(.smooth(duration: 0.38), value: isExpanded)
        .animation(.smooth(duration: 0.26), value: selectedTab)
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
                    .fill(iconStore.accentStyle(opacity: 0.12))
            }
        }

        let button = Button {
            appCoordinator.requestTabBarVisible(true)
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

    private func mirroredContent(
        items: [NuvioTabBarItemMetrics],
        metrics: NuvioTabBarMetrics
    ) -> some View {
        let selectedIndex = visibleTabs.firstIndex(of: selectedTab)
        return ZStack {
            if let selectedIndex, let selection = metrics.selectionFrame(forItemAt: selectedIndex) {
                Capsule()
                    .fill(Color.black.opacity(0.42))
                    .frame(width: selection.width, height: selection.height)
                    .position(x: selection.midX, y: selection.midY)
            }
            ForEach(Array(visibleTabs.enumerated()), id: \.element) { entry in
                mirroredItem(tab: entry.element, item: items[entry.offset])
            }
        }
        .frame(width: metrics.width, height: metrics.height)
    }

    @ViewBuilder
    private func mirroredItem(tab: NuvioAppTab, item: NuvioTabBarItemMetrics) -> some View {
        let selected = tab == selectedTab
        nativeIcon(for: tab, selected: selected)
            .frame(width: item.iconFrame.width, height: item.iconFrame.height)
            .position(x: item.iconFrame.midX, y: item.iconFrame.midY)
            .transition(.opacity)
        if let labelFrame = item.labelFrame {
            Text(appCoordinator.title(for: tab))
                .font(item.labelFont.map { Font($0 as CTFont) } ?? .system(size: 10, weight: .semibold))
                .lineLimit(1)
                .fixedSize()
                .foregroundStyle(selected ? iconStore.accentStyle() : AnyShapeStyle(Color.white))
                .position(x: labelFrame.midX, y: labelFrame.midY)
                .transition(.opacity)
        }
    }

    private func nativeIcon(for tab: NuvioAppTab, selected: Bool) -> some View {
        let image = Image(uiImage: iconStore.image(for: tab, selected: selected))
        return Group {
            if tab == .settings {
                image.renderingMode(.original).resizable().scaledToFit()
            } else if selected {
                image.renderingMode(.template).resizable().scaledToFit()
                    .foregroundStyle(iconStore.accentStyle())
            } else {
                image.renderingMode(.template).resizable().scaledToFit()
                    .foregroundStyle(Color.white)
            }
        }
    }

    private func label(for tab: NuvioAppTab, selected: Bool) -> some View {
        Text(appCoordinator.title(for: tab))
            .font(.system(size: 11, weight: .medium))
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .foregroundStyle(
                selected ? iconStore.accentStyle() : AnyShapeStyle(Color.white)
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
                    .foregroundStyle(iconStore.accentStyle())
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
