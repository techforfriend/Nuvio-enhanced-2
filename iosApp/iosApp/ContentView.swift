import Combine
import SwiftUI
import UIKit
import ComposeApp

private let nuvioBackgroundColor = UIColor(
    red: 0.008,
    green: 0.016,
    blue: 0.016,
    alpha: 1.0
)

private enum NuvioComposeHost {
    static let registerPlayerBridge: Void = {
        NuvioPlayerRegistration.register()
    }()

    static func wrap(_ contentController: UIViewController) -> RootComposeViewController {
        _ = registerPlayerBridge
        contentController.view.backgroundColor = nuvioBackgroundColor
        return RootComposeViewController(contentController: contentController)
    }
}

/// A navigation-neutral container for Compose. The MPV player is nested below the
/// Compose controller, so UIKit's immersive-system-UI queries need to be forwarded
/// to the deepest child that requests them.
final class RootComposeViewController: UIViewController {
    private let contentController: UIViewController

    init(contentController: UIViewController) {
        self.contentController = contentController
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = nuvioBackgroundColor
        contentController.view.backgroundColor = nuvioBackgroundColor

        addChild(contentController)
        view.addSubview(contentController.view)
        contentController.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            contentController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            contentController.view.topAnchor.constraint(equalTo: view.topAnchor),
            contentController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        contentController.didMove(toParent: self)
    }

    override var childForHomeIndicatorAutoHidden: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var childForScreenEdgesDeferringSystemGestures: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var childForStatusBarHidden: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        immersiveController(in: contentController)?.prefersHomeIndicatorAutoHidden ?? false
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        immersiveController(in: contentController)?.preferredScreenEdgesDeferringSystemGestures ?? []
    }

    override var prefersStatusBarHidden: Bool {
        immersiveController(in: contentController)?.prefersStatusBarHidden ?? false
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()
    }

    private func immersiveController(in controller: UIViewController?) -> UIViewController? {
        guard let controller else { return nil }

        if controller.prefersHomeIndicatorAutoHidden ||
            !controller.preferredScreenEdgesDeferringSystemGestures.isEmpty ||
            controller.prefersStatusBarHidden {
            return controller
        }

        if let presented = immersiveController(in: controller.presentedViewController) {
            return presented
        }

        for child in controller.children.reversed() {
            if let immersiveChild = immersiveController(in: child) {
                return immersiveChild
            }
        }

        return nil
    }
}

// MARK: - UIKit fallback

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        NuvioComposeHost.wrap(MainViewControllerKt.MainViewController())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// MARK: - Native iOS navigation

@available(iOS 16.0, *)
struct RouteWrapper: Hashable, Identifiable {
    let id = UUID()
    let route: AppRoute

    static func == (lhs: RouteWrapper, rhs: RouteWrapper) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

@available(iOS 16.0, *)
@MainActor
final class TabNavigationCoordinator: ObservableObject {
    @Published var path: [RouteWrapper] = []

    func push(_ route: AppRoute, launchSingleTop: Bool) {
        if launchSingleTop,
           path.last?.route.navigationIdentity == route.navigationIdentity {
            AppKt.disposeRoute(route: route)
            return
        }
        path.append(RouteWrapper(route: route))
    }

    func pop() {
        guard !path.isEmpty else { return }
        var updatedPath = path
        updatedPath.removeLast()
        setPath(updatedPath)
    }

    func replace(_ route: AppRoute) {
        var updatedPath = path
        if updatedPath.isEmpty {
            updatedPath.append(RouteWrapper(route: route))
        } else {
            updatedPath[updatedPath.index(before: updatedPath.endIndex)] = RouteWrapper(route: route)
        }
        setPath(updatedPath)
    }

    func popToRoot() {
        setPath([])
    }

    /// Used by NavigationStack's path binding so interactive swipe-back and
    /// programmatic mutations share the same Kotlin route-disposal behavior.
    func setPath(_ newPath: [RouteWrapper]) {
        let retainedIDs = Set(newPath.map(\.id))
        let removedRoutes = path
            .filter { !retainedIDs.contains($0.id) }
            .map(\.route)

        path = newPath
        removedRoutes.forEach { AppKt.disposeRoute(route: $0) }
    }
}

@available(iOS 16.0, *)
enum NuvioAppTab: String, CaseIterable, Hashable {
    case home = "Home"
    case search = "Search"
    case library = "Library"
    case settings = "Settings"

    var fallbackTitle: String {
        String(localized: String.LocalizationValue(rawValue))
    }

    static func from(kotlinName: String?) -> NuvioAppTab? {
        switch kotlinName?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "home": return .home
        case "search": return .search
        case "library": return .library
        case "settings", "profile": return .settings
        default: return nil
        }
    }

    var iconAssetName: String {
        switch self {
        case .home: return "NuvioTabHome"
        case .search: return "NuvioTabSearch"
        case .library: return "NuvioTabLibrary"
        case .settings: return "NuvioTabProfile"
        }
    }

    var fallbackSystemImage: String {
        switch self {
        case .home: return "house.fill"
        case .search: return "magnifyingglass"
        case .library: return "rectangle.stack.fill"
        case .settings: return "person.crop.circle.fill"
        }
    }
}

private enum NuvioNativeTabIcon {
    private static let legacyStaticIconSize = CGSize(width: 25, height: 25)

    static func image(for tab: NuvioAppTab) -> UIImage {
        if let asset = UIImage(named: tab.iconAssetName) {
            return UIGraphicsImageRenderer(size: legacyStaticIconSize).image { _ in
                asset
                    .withRenderingMode(.alwaysOriginal)
                    .draw(in: CGRect(origin: .zero, size: legacyStaticIconSize))
            }.withRenderingMode(.alwaysTemplate)
        }

        return (UIImage(systemName: tab.fallbackSystemImage) ?? UIImage())
            .withRenderingMode(.alwaysTemplate)
    }

    static func profileAvatar(
        name: String?,
        avatarColor: UIColor?,
        backgroundColor: UIColor?,
        avatarImage: UIImage?,
        selected: Bool,
        accent: UIColor
    ) -> UIImage {
        guard name != nil || avatarColor != nil || avatarImage != nil else {
            return image(for: .settings)
        }

        let size = CGSize(width: 28, height: 28)
        let baseColor = avatarColor
            ?? UIColor(red: 30 / 255, green: 136 / 255, blue: 229 / 255, alpha: 1)
        let fillColor = backgroundColor ?? baseColor.withAlphaComponent(0.15)
        let borderColor = selected ? accent : baseColor.withAlphaComponent(0.5)
        let initial = name?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(1)
            .uppercased() ?? ""

        return UIGraphicsImageRenderer(size: size).image { _ in
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1)
            fillColor.setFill()
            UIBezierPath(ovalIn: rect).fill()

            if let avatarImage {
                UIBezierPath(ovalIn: rect).addClip()
                drawAspectFill(image: avatarImage, in: rect)
            } else if !initial.isEmpty {
                let font = UIFont.systemFont(ofSize: size.height * 0.45, weight: .bold)
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: baseColor,
                ]
                let textSize = initial.size(withAttributes: attributes)
                initial.draw(
                    at: CGPoint(
                        x: rect.midX - textSize.width / 2,
                        y: rect.midY - textSize.height / 2
                    ),
                    withAttributes: attributes
                )
            } else {
                image(for: .settings)
                    .withTintColor(baseColor, renderingMode: .alwaysOriginal)
                    .draw(in: rect.insetBy(dx: 5.5, dy: 5.5))
            }

            borderColor.setStroke()
            let borderPath = UIBezierPath(ovalIn: rect.insetBy(dx: 0.75, dy: 0.75))
            borderPath.lineWidth = 1.5
            borderPath.stroke()
        }.withRenderingMode(.alwaysOriginal)
    }

    private static func drawAspectFill(image: UIImage, in rect: CGRect) {
        guard image.size.width > 0, image.size.height > 0 else { return }
        let scale = max(rect.width / image.size.width, rect.height / image.size.height)
        let drawSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        image.draw(
            in: CGRect(
                x: rect.midX - drawSize.width / 2,
                y: rect.midY - drawSize.height / 2,
                width: drawSize.width,
                height: drawSize.height
            )
        )
    }
}

@available(iOS 16.0, *)
final class NativeTabIconStore: ObservableObject {
    private static let chromeDidChange = Notification.Name("NuvioNativeTabChromeDidChange")
    private static let accentKey = "NuvioNativeTabAccentColor"
    private static let profileNameKey = "NuvioNativeProfileName"
    private static let profileColorKey = "NuvioNativeProfileAvatarColor"
    private static let profileURLKey = "NuvioNativeProfileAvatarURL"
    private static let profileBackgroundKey = "NuvioNativeProfileAvatarBackgroundColor"

    @Published private(set) var revision = 0
    @Published private(set) var accentColor = UIColor(
        red: 0.96,
        green: 0.96,
        blue: 0.96,
        alpha: 1
    )

    private var observer: NSObjectProtocol?
    private var profileAvatarURL: String?
    private var profileAvatarImage: UIImage?
    private var profileAvatarTask: URLSessionDataTask?

    init() {
        UITabBar.appearance().unselectedItemTintColor = UIColor(
            red: 150 / 255,
            green: 156 / 255,
            blue: 163 / 255,
            alpha: 1
        )
        observer = NotificationCenter.default.addObserver(
            forName: Self.chromeDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.reload()
        }
        reload()
    }

    deinit {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        profileAvatarTask?.cancel()
    }

    func image(for tab: NuvioAppTab, selected: Bool) -> UIImage {
        guard tab == .settings else {
            return NuvioNativeTabIcon.image(for: tab)
        }

        let defaults = UserDefaults.standard
        return NuvioNativeTabIcon.profileAvatar(
            name: defaults.string(forKey: Self.profileNameKey),
            avatarColor: UIColor(hexString: defaults.string(forKey: Self.profileColorKey)),
            backgroundColor: UIColor(hexString: defaults.string(forKey: Self.profileBackgroundKey)),
            avatarImage: profileAvatarImage,
            selected: selected,
            accent: accentColor
        )
    }

    private func reload() {
        let defaults = UserDefaults.standard
        accentColor = UIColor(hexString: defaults.string(forKey: Self.accentKey))
            ?? UIColor(red: 0.96, green: 0.96, blue: 0.96, alpha: 1)

        let nextURL = defaults.string(forKey: Self.profileURLKey)
        guard nextURL != profileAvatarURL else {
            revision &+= 1
            return
        }

        profileAvatarTask?.cancel()
        profileAvatarTask = nil
        profileAvatarURL = nextURL
        profileAvatarImage = nil
        revision &+= 1

        guard let nextURL, let url = URL(string: nextURL) else { return }
        profileAvatarTask = URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, self.profileAvatarURL == nextURL else { return }
                self.profileAvatarImage = image
                self.revision &+= 1
            }
        }
        profileAvatarTask?.resume()
    }
}

@available(iOS 16.0, *)
@MainActor
final class AppNavigationCoordinator: ObservableObject {
    @Published var selectedTab: NuvioAppTab = .home
    @Published private(set) var isAppReady = false
    @Published private var localizedTabTitles: [NuvioAppTab: String] = [:]

    let homeCoordinator = TabNavigationCoordinator()
    let searchCoordinator = TabNavigationCoordinator()
    let libraryCoordinator = TabNavigationCoordinator()
    let settingsCoordinator = TabNavigationCoordinator()

    private var allCoordinators: [TabNavigationCoordinator] {
        [homeCoordinator, searchCoordinator, libraryCoordinator, settingsCoordinator]
    }

    func coordinator(for tab: NuvioAppTab) -> TabNavigationCoordinator {
        switch tab {
        case .home: return homeCoordinator
        case .search: return searchCoordinator
        case .library: return libraryCoordinator
        case .settings: return settingsCoordinator
        }
    }

    func activateTab(named tabName: String) {
        guard let tab = NuvioAppTab.from(kotlinName: tabName) else { return }
        if tab == .home || isAppReady {
            selectedTab = tab
        }
    }

    func title(for tab: NuvioAppTab) -> String {
        localizedTabTitles[tab] ?? tab.fallbackTitle
    }

    func updateTabTitles(home: String, search: String, library: String, profile: String) {
        localizedTabTitles = [
            .home: home,
            .search: search,
            .library: library,
            .settings: profile,
        ]
    }

    func updateAppReady(_ ready: Bool) {
        isAppReady = ready
        if !ready {
            selectedTab = .home
            allCoordinators.forEach { $0.popToRoot() }
        }
    }

    func tab(for target: TabNavigationCoordinator) -> NuvioAppTab? {
        NuvioAppTab.allCases.first { coordinator(for: $0) === target }
    }

    func push(
        _ route: AppRoute,
        from origin: TabNavigationCoordinator,
        launchSingleTop: Bool
    ) {
        guard isAppReady else {
            AppKt.disposeRoute(route: route)
            return
        }
        let targetTab = NuvioAppTab.from(kotlinName: route.preferredTabName)
            ?? tab(for: origin)
            ?? selectedTab
        let target = coordinator(for: targetTab)
        selectedTab = targetTab
        target.push(route, launchSingleTop: launchSingleTop)
    }

    func replace(_ route: AppRoute, in target: TabNavigationCoordinator) {
        guard isAppReady else {
            AppKt.disposeRoute(route: route)
            return
        }
        if let targetTab = tab(for: target) {
            selectedTab = targetTab
        }
        target.replace(route)
    }
}

@available(iOS 16.0, *)
struct NativeNavComposeView: UIViewControllerRepresentable {
    let tab: NuvioAppTab
    let coordinator: TabNavigationCoordinator
    let appCoordinator: AppNavigationCoordinator

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(
            initialTabName: tab.rawValue,
            onNavigate: { route, launchSingleTop in
                appCoordinator.push(
                    route,
                    from: coordinator,
                    launchSingleTop: launchSingleTop.boolValue
                )
            },
            onGoBack: {
                coordinator.pop()
            },
            onReplace: { route in
                appCoordinator.replace(route, in: coordinator)
            },
            onActivate: { tabName in
                appCoordinator.activateTab(named: tabName)
            },
            onAppReady: { ready in
                appCoordinator.updateAppReady(ready.boolValue)
            },
            onTabTitles: { home, search, library, profile in
                appCoordinator.updateTabTitles(
                    home: home,
                    search: search,
                    library: library,
                    profile: profile
                )
            }
        )
        return NuvioComposeHost.wrap(controller)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@available(iOS 16.0, *)
struct DetailComposeView: UIViewControllerRepresentable {
    let route: AppRoute
    let coordinator: TabNavigationCoordinator
    let appCoordinator: AppNavigationCoordinator

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.ScreenViewController(
            route: route,
            onNavigate: { newRoute, launchSingleTop in
                appCoordinator.push(
                    newRoute,
                    from: coordinator,
                    launchSingleTop: launchSingleTop.boolValue
                )
            },
            onGoBack: {
                coordinator.pop()
            },
            onReplace: { newRoute in
                appCoordinator.replace(newRoute, in: coordinator)
            },
            onActivate: { tabName in
                appCoordinator.activateTab(named: tabName)
            }
        )
        return NuvioComposeHost.wrap(controller)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@available(iOS 16.0, *)
struct TabContentView: View {
    let tab: NuvioAppTab
    @ObservedObject var coordinator: TabNavigationCoordinator
    @ObservedObject var appCoordinator: AppNavigationCoordinator

    var body: some View {
        NavigationStack(
            path: Binding(
                get: { coordinator.path },
                set: { coordinator.setPath($0) }
            )
        ) {
            NativeNavComposeView(
                tab: tab,
                coordinator: coordinator,
                appCoordinator: appCoordinator
            )
            .ignoresSafeArea(.all)
            .navigationTitle(appCoordinator.title(for: tab))
            .navigationBarHidden(true)
            .navigationDestination(for: RouteWrapper.self) { wrapper in
                if appCoordinator.selectedTab == tab {
                    DetailDestinationView(
                        wrapper: wrapper,
                        coordinator: coordinator,
                        appCoordinator: appCoordinator
                    )
                } else {
                    Color.clear
                }
            }
        }
        // Tab-bar visibility is a preference emitted by the active navigation
        // stack. Applying it here keeps the authentication/profile gate truly
        // full-screen on iOS 26, where a modifier on TabView itself is ignored.
        .toolbar(
            appCoordinator.isAppReady && coordinator.path.isEmpty
                ? Visibility.visible
                : Visibility.hidden,
            for: .tabBar
        )
    }
}

@available(iOS 16.0, *)
private struct NativeToolbarReadabilityFade: View {
    var body: some View {
        Rectangle()
            .fill(.ultraThinMaterial)
            .mask(
                LinearGradient(
                    stops: [
                        .init(color: .black, location: 0),
                        .init(color: .black.opacity(0.78), location: 0.55),
                        .init(color: .clear, location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .frame(height: 120)
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

@available(iOS 16.0, *)
private struct DetailDestinationView: View {
    let wrapper: RouteWrapper
    @ObservedObject var coordinator: TabNavigationCoordinator
    @ObservedObject var appCoordinator: AppNavigationCoordinator

    private var isMetaDetail: Bool {
        wrapper.route is DetailRoute
    }

    private var showsReadabilityFade: Bool {
        !wrapper.route.hidesNavigationBar && !isMetaDetail
    }

    private var content: some View {
        ZStack(alignment: .top) {
            DetailComposeView(
                route: wrapper.route,
                coordinator: coordinator,
                appCoordinator: appCoordinator
            )
            .ignoresSafeArea(.all)

            if showsReadabilityFade {
                NativeToolbarReadabilityFade()
            }
        }
        .navigationTitle(isMetaDetail ? "" : wrapper.route.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(isMetaDetail ? .editor : .automatic)
        .toolbar(.hidden, for: .tabBar)
        .toolbar(
            wrapper.route.hidesNavigationBar ? Visibility.hidden : Visibility.visible,
            for: .navigationBar
        )
    }

    @ViewBuilder
    var body: some View {
        if #available(iOS 26.0, *), !isMetaDetail {
            content.navigationSubtitle(wrapper.route.subtitle ?? "")
        } else {
            content
        }
    }
}

@available(iOS 16.0, *)
struct NativeNavContentView: View {
    @StateObject private var appCoordinator = AppNavigationCoordinator()
    @StateObject private var iconStore = NativeTabIconStore()

    private var tabs: some View {
        TabView(
            selection: Binding(
                get: { appCoordinator.selectedTab },
                set: { newTab in
                    if newTab == appCoordinator.selectedTab {
                        NativeTabBridgeKt.nativeTabSelect(tabName: newTab.rawValue)
                        return
                    }
                    if appCoordinator.isAppReady || newTab == .home {
                        appCoordinator.selectedTab = newTab
                    }
                }
            )
        ) {
            ForEach(NuvioAppTab.allCases, id: \.self) { tab in
                TabContentView(
                    tab: tab,
                    coordinator: appCoordinator.coordinator(for: tab),
                    appCoordinator: appCoordinator
                )
                .tabItem {
                    Label {
                        Text(appCoordinator.title(for: tab))
                    } icon: {
                        Image(
                            uiImage: iconStore.image(
                                for: tab,
                                selected: appCoordinator.selectedTab == tab
                            )
                        )
                        .id(
                            "\(tab.rawValue)-\(iconStore.revision)-" +
                                "\(appCoordinator.selectedTab == tab)"
                        )
                    }
                }
                .tag(tab)
            }
        }
        .tint(Color(uiColor: iconStore.accentColor))
    }

    @ViewBuilder
    var body: some View {
        if #available(iOS 26.0, *) {
            tabs.tabBarMinimizeBehavior(.automatic)
        } else {
            tabs
        }
    }
}

struct ContentView: View {
    var body: some View {
        if #available(iOS 16.0, *) {
            NativeNavContentView()
        } else {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}

private extension UIColor {
    convenience init?(hexString: String?) {
        guard var value = hexString?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else {
            return nil
        }
        if value.hasPrefix("#") {
            value.removeFirst()
        }
        guard value.count == 6, let rgb = UInt64(value, radix: 16) else {
            return nil
        }
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
