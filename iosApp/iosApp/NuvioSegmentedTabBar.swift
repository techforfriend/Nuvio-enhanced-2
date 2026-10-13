import SwiftUI
import UIKit

@available(iOS 26.0, *)
struct NuvioSegmentedTabBarItem {
    let title: String
    let baseImage: UIImage
    let accentImage: UIImage
    let tintsBaseImage: Bool
    let tintsAccentImage: Bool
}

@available(iOS 26.0, *)
struct NuvioSegmentedTabBar: UIViewRepresentable {
    static let barHeight: CGFloat = 62
    static let compactBarHeight: CGFloat = 44

    static func height(compact: Bool) -> CGFloat {
        compact ? compactBarHeight : barHeight
    }

    let items: [NuvioSegmentedTabBarItem]
    let contentKey: String
    let selectedIndex: Int
    let isExpanded: Bool
    let isCompact: Bool
    let accentColor: UIColor
    let indicatorColor: UIColor
    let hapticsEnabled: () -> Bool
    let onSelect: (Int) -> Void
    let onReselect: (Int) -> Void
    let onLongPress: (Int) -> Void
    let onCollapsedTap: () -> Void

    func makeUIView(context: Context) -> NuvioSegmentedTabBarView {
        let view = NuvioSegmentedTabBarView()
        apply(to: view)
        return view
    }

    func updateUIView(_ view: NuvioSegmentedTabBarView, context: Context) {
        apply(to: view)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: NuvioSegmentedTabBarView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 360, height: Self.height(compact: isCompact))
    }

    private func apply(to view: NuvioSegmentedTabBarView) {
        let control = view.segmentedControl
        control.hapticsEnabled = hapticsEnabled
        control.onSelect = onSelect
        control.onReselect = onReselect
        control.onLongPress = onLongPress
        control.activeTintColor = accentColor
        control.indicatorColor = indicatorColor
        view.setCompact(isCompact)
        if view.appliedContentKey != contentKey {
            view.appliedContentKey = contentKey
            control.setItems(items, horizontal: isCompact)
        }
        if !control.isTrackingTouch, control.selectedSegmentIndex != selectedIndex {
            control.selectedSegmentIndex = selectedIndex
        }
        view.onCollapsedTap = onCollapsedTap
        view.onCollapsedLongPress = { onLongPress(selectedIndex) }
        if items.indices.contains(selectedIndex) {
            let item = items[selectedIndex]
            view.setCollapsedIcon(item.accentImage, tinted: item.tintsAccentImage, tint: accentColor)
        }
        view.setExpanded(isExpanded)
    }
}

@available(iOS 26.0, *)
final class NuvioSegmentedTabBarView: UIView {
    private var collapsedSize: CGSize {
        isCompact ? CGSize(width: 56, height: 40) : CGSize(width: 56, height: 48)
    }
    private static let expandedInset: CGFloat = 4

    let segmentedControl = NuvioTabSegmentedControl(items: nil)
    var appliedContentKey: String?
    var onCollapsedTap: () -> Void = {}
    var onCollapsedLongPress: () -> Void = {}

    private let glassView: UIVisualEffectView
    private let collapsedIcon = UIImageView()
    private var isExpanded = true
    private var isCompact = false
    private var hasLaidOut = false
    private var leadingConstraint: NSLayoutConstraint!
    private var widthConstraint: NSLayoutConstraint!
    private var heightConstraint: NSLayoutConstraint!
    private var controlWidthConstraint: NSLayoutConstraint!
    private var controlHeightConstraint: NSLayoutConstraint!
    private lazy var collapsedTap = UITapGestureRecognizer(target: self, action: #selector(handleCollapsedTap))
    private lazy var collapsedLongPress: UILongPressGestureRecognizer = {
        let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(handleCollapsedLongPress(_:)))
        recognizer.minimumPressDuration = 0.45
        return recognizer
    }()

    override init(frame: CGRect) {
        let glass = UIGlassEffect()
        glass.isInteractive = true
        glassView = UIVisualEffectView(effect: glass)
        super.init(frame: frame)
        backgroundColor = .clear

        addSubview(glassView)
        glassView.translatesAutoresizingMaskIntoConstraints = false
        glassView.contentView.clipsToBounds = true
        glassView.contentView.addSubview(segmentedControl)
        segmentedControl.translatesAutoresizingMaskIntoConstraints = false
        glassView.contentView.addSubview(collapsedIcon)
        collapsedIcon.translatesAutoresizingMaskIntoConstraints = false
        collapsedIcon.contentMode = .scaleAspectFit
        collapsedIcon.alpha = 0

        leadingConstraint = glassView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Self.expandedInset)
        widthConstraint = glassView.widthAnchor.constraint(equalToConstant: 300)
        heightConstraint = glassView.heightAnchor.constraint(equalToConstant: NuvioSegmentedTabBar.barHeight)
        controlWidthConstraint = segmentedControl.widthAnchor.constraint(equalToConstant: 296)
        controlHeightConstraint = segmentedControl.heightAnchor.constraint(equalToConstant: NuvioSegmentedTabBar.barHeight - 5)

        let padding: CGFloat = 2
        NSLayoutConstraint.activate([
            leadingConstraint,
            widthConstraint,
            heightConstraint,
            glassView.bottomAnchor.constraint(equalTo: bottomAnchor),
            segmentedControl.leadingAnchor.constraint(equalTo: glassView.contentView.leadingAnchor, constant: padding),
            controlWidthConstraint,
            segmentedControl.topAnchor.constraint(equalTo: glassView.contentView.topAnchor, constant: padding),
            controlHeightConstraint,
            collapsedIcon.centerXAnchor.constraint(equalTo: glassView.contentView.centerXAnchor),
            collapsedIcon.centerYAnchor.constraint(equalTo: glassView.contentView.centerYAnchor),
            collapsedIcon.widthAnchor.constraint(equalToConstant: 24),
            collapsedIcon.heightAnchor.constraint(equalToConstant: 24),
        ])

        glassView.addGestureRecognizer(collapsedTap)
        glassView.addGestureRecognizer(collapsedLongPress)
        collapsedTap.isEnabled = false
        collapsedLongPress.isEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setCollapsedIcon(_ image: UIImage, tinted: Bool, tint: UIColor) {
        collapsedIcon.image = tinted ? image.withRenderingMode(.alwaysTemplate) : image.withRenderingMode(.alwaysOriginal)
        collapsedIcon.tintColor = tint
    }

    func setCompact(_ compact: Bool) {
        guard compact != isCompact else { return }
        isCompact = compact
        applyState()
    }

    private var expandedFrame: (leading: CGFloat, width: CGFloat) {
        let available = max(bounds.width - Self.expandedInset * 2, collapsedSize.width)
        guard isCompact else { return (Self.expandedInset, available) }
        let width = min(available, max(segmentedControl.preferredCompactWidth, collapsedSize.width))
        return ((bounds.width - width) / 2, width)
    }

    func setExpanded(_ expanded: Bool) {
        guard expanded != isExpanded else { return }
        isExpanded = expanded
        guard hasLaidOut, window != nil else {
            applyState()
            return
        }
        layoutIfNeeded()
        UIView.animate(
            withDuration: 0.5,
            delay: 0,
            usingSpringWithDamping: 0.82,
            initialSpringVelocity: 0,
            options: [.beginFromCurrentState, .allowUserInteraction]
        ) {
            self.applyState()
            self.layoutIfNeeded()
        }
    }

    private func applyState() {
        let expanded = expandedFrame
        let barHeight = NuvioSegmentedTabBar.height(compact: isCompact)
        leadingConstraint.constant = isExpanded ? expanded.leading : 0
        widthConstraint.constant = isExpanded ? expanded.width : collapsedSize.width
        heightConstraint.constant = isExpanded ? barHeight : collapsedSize.height
        controlWidthConstraint.constant = expanded.width - 4
        controlHeightConstraint.constant = barHeight - 5
        segmentedControl.alpha = isExpanded ? 1 : 0
        collapsedIcon.alpha = isExpanded ? 0 : 1
        segmentedControl.isUserInteractionEnabled = isExpanded
        collapsedTap.isEnabled = !isExpanded
        collapsedLongPress.isEnabled = !isExpanded
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        glassView.cornerConfiguration = .capsule()
        let expanded = expandedFrame
        if !hasLaidOut
            || (isExpanded && (widthConstraint.constant != expanded.width || leadingConstraint.constant != expanded.leading))
            || controlWidthConstraint.constant != expanded.width - 4 {
            hasLaidOut = bounds.width > 0
            applyState()
        }
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        glassView.frame.contains(point)
    }

    @objc private func handleCollapsedTap() {
        onCollapsedTap()
    }

    @objc private func handleCollapsedLongPress(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        onCollapsedLongPress()
    }
}

@available(iOS 26.0, *)
final class NuvioTabSegmentedControl: UISegmentedControl {
    var hapticsEnabled: () -> Bool = { true }
    var onSelect: (Int) -> Void = { _ in }
    var onReselect: (Int) -> Void = { _ in }
    var onLongPress: (Int) -> Void = { _ in }

    var activeTintColor: UIColor = .tintColor {
        didSet {
            guard activeTintColor != oldValue else { return }
            accentViews.forEach { $0.tintColor = activeTintColor }
        }
    }

    var indicatorColor: UIColor = .tintColor {
        didSet {
            guard indicatorColor != oldValue else { return }
            applySelectionTint()
        }
    }

    private func applySelectionTint() {
        selectedSegmentTintColor = indicatorColor.withAlphaComponent(0.24)
    }

    private(set) var isTrackingTouch = false

    private static let baseViewTag = 8_801
    private static let accentViewTag = 8_802
    private var baseViews: [NuvioTabItemContentView] = []
    private var accentViews: [NuvioTabItemContentView] = []
    private var originalIndex: Int?
    private weak var cachedLensView: UIView?
    private var displayLink: CADisplayLink?
    private var displayLinkProxy: NuvioDisplayLinkProxy?
    private var lastLensRect: CGRect = .zero
    private var stableFrames = 0

    private let feedback: UIImpactFeedbackGenerator = {
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.prepare()
        return generator
    }()

    override init(items: [Any]?) {
        super.init(items: items)
        accessibilityTraits = .tabBar
        applySelectionTint()
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.45
        addGestureRecognizer(longPress)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    var preferredCompactWidth: CGFloat {
        let widest = baseViews.map(\.intrinsicContentSize.width).max() ?? 60
        return CGFloat(numberOfSegments) * (widest + 24) + 4
    }

    func setItems(_ items: [NuvioSegmentedTabBarItem], horizontal: Bool) {
        let keepIndex = selectedSegmentIndex
        if numberOfSegments != items.count {
            removeAllSegments()
            for (index, item) in items.enumerated() {
                insertSegment(withTitle: item.title, at: index, animated: false)
            }
        } else {
            for (index, item) in items.enumerated() {
                setTitle(item.title, forSegmentAt: index)
            }
        }
        if keepIndex >= 0, keepIndex < numberOfSegments {
            selectedSegmentIndex = keepIndex
        }

        for segment in segmentViews() {
            segment.viewWithTag(Self.baseViewTag)?.removeFromSuperview()
            segment.viewWithTag(Self.accentViewTag)?.removeFromSuperview()
        }
        cachedLensView = nil
        baseViews = items.map {
            let view = NuvioTabItemContentView(title: $0.title, image: $0.baseImage, tintsImage: $0.tintsBaseImage, horizontal: horizontal)
            view.tintColor = .white
            view.drawsLegibilityShadow = true
            return view
        }
        accentViews = items.map {
            let view = NuvioTabItemContentView(title: $0.title, image: $0.accentImage, tintsImage: $0.tintsAccentImage, horizontal: horizontal)
            view.tintColor = activeTintColor
            return view
        }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        for subview in subviews where subview is UIImageView {
            subview.alpha = 0
        }
        hideLabels(in: self)
        injectItemViews()
        wakeDisplayLink()
    }

    override func didAddSubview(_ subview: UIView) {
        super.didAddSubview(subview)
        hideLabels(in: subview)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            startDisplayLink()
        } else {
            displayLink?.invalidate()
            displayLink = nil
            displayLinkProxy = nil
        }
    }

    // MARK: Segment content

    private func segmentViews() -> [UIView] {
        var found: [UIView] = []
        func search(_ view: UIView) {
            for subview in view.subviews {
                if String(describing: type(of: subview)) == "UISegment" {
                    found.append(subview)
                } else {
                    search(subview)
                }
            }
        }
        search(self)
        return found.sorted { $0.frame.minX < $1.frame.minX }
    }

    private func hideLabels(in view: UIView) {
        if let label = view as? UILabel,
           label.superview?.tag != Self.baseViewTag,
           label.superview?.tag != Self.accentViewTag {
            label.isHidden = true
        }
        view.subviews.forEach(hideLabels(in:))
    }

    private func injectItemViews() {
        let segments = segmentViews()
        guard segments.count == baseViews.count, segments.count == accentViews.count else { return }
        for (index, segment) in segments.enumerated() {
            if segment.viewWithTag(Self.baseViewTag) == nil {
                attach(baseViews[index], tag: Self.baseViewTag, to: segment)
            }
            if segment.viewWithTag(Self.accentViewTag) == nil {
                let accent = accentViews[index]
                attach(accent, tag: Self.accentViewTag, to: segment)
                let mask = CAShapeLayer()
                mask.path = UIBezierPath(rect: .zero).cgPath
                accent.layer.mask = mask
            }
        }
    }

    private func attach(_ view: NuvioTabItemContentView, tag: Int, to segment: UIView) {
        view.tag = tag
        view.translatesAutoresizingMaskIntoConstraints = false
        segment.addSubview(view)
        let size = view.intrinsicContentSize
        NSLayoutConstraint.activate([
            view.centerXAnchor.constraint(equalTo: segment.centerXAnchor),
            view.centerYAnchor.constraint(equalTo: segment.centerYAnchor),
            view.widthAnchor.constraint(equalToConstant: size.width),
            view.heightAnchor.constraint(equalToConstant: size.height),
        ])
    }

    // MARK: Accent masking to the lens

    private func startDisplayLink() {
        guard displayLink == nil else { return }
        let proxy = NuvioDisplayLinkProxy(control: self)
        displayLinkProxy = proxy
        let link = CADisplayLink(target: proxy, selector: #selector(NuvioDisplayLinkProxy.tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func wakeDisplayLink() {
        displayLink?.isPaused = false
        stableFrames = 0
    }

    private func lensRect() -> CGRect {
        if cachedLensView == nil {
            cachedLensView = findLensView()
        }
        let selfLayer = layer.presentation() ?? layer
        if let lens = cachedLensView {
            let lensLayer = lens.layer.presentation() ?? lens.layer
            return selfLayer.convert(lensLayer.bounds, from: lensLayer)
        }
        let segments = segmentViews()
        guard selectedSegmentIndex >= 0, selectedSegmentIndex < segments.count else { return .zero }
        return segments[selectedSegmentIndex].frame
    }

    private func findLensView() -> UIView? {
        func search(_ view: UIView) -> UIView? {
            for subview in view.subviews {
                if String(describing: type(of: subview)) == "_UILiquidLensView" { return subview }
                if let found = search(subview) { return found }
            }
            return nil
        }
        if let lens = search(self) { return lens }
        guard let container = segmentViews().first?.superview, let wrapper = container.superview else { return nil }
        return wrapper.subviews.first { $0 !== container && !$0.subviews.isEmpty }
    }

    fileprivate func updateAccentMasks() {
        let rect = lensRect()
        if rect == lastLensRect {
            stableFrames += 1
            if stableFrames >= 3 {
                displayLink?.isPaused = true
                return
            }
        } else {
            stableFrames = 0
            lastLensRect = rect
        }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let selfLayer = layer.presentation() ?? layer
        for (base, accent) in zip(baseViews, accentViews) {
            let accentLayer = accent.layer.presentation() ?? accent.layer
            let frameInControl = selfLayer.convert(accentLayer.bounds, from: accentLayer)
            let local = rect.offsetBy(dx: -frameInControl.minX, dy: -frameInControl.minY)
            let capsule = UIBezierPath(roundedRect: local, cornerRadius: rect.height / 2)

            let accentMask = (accent.layer.mask as? CAShapeLayer) ?? CAShapeLayer()
            accentMask.path = capsule.cgPath
            accent.layer.mask = accentMask

            if rect.intersects(frameInControl) {
                let baseMask = (base.layer.mask as? CAShapeLayer) ?? CAShapeLayer()
                let path = UIBezierPath(rect: base.bounds)
                path.append(capsule)
                baseMask.fillRule = .evenOdd
                baseMask.path = path.cgPath
                base.layer.mask = baseMask
            } else {
                base.layer.mask = nil
            }
        }
        CATransaction.commit()
    }

    // MARK: Touch handling

    private func segmentIndex(at point: CGPoint) -> Int {
        guard numberOfSegments > 0 else { return 0 }
        let width = bounds.width / CGFloat(numberOfSegments)
        return min(max(Int(point.x / width), 0), numberOfSegments - 1)
    }

    private var movesLensOnTouchDown: Bool {
        !traitCollection.preferredContentSizeCategory.isAccessibilityCategory
    }

    private func tick() {
        guard hapticsEnabled() else { return }
        feedback.impactOccurred()
        feedback.prepare()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        isTrackingTouch = true
        wakeDisplayLink()
        if let touch = touches.first, movesLensOnTouchDown {
            originalIndex = selectedSegmentIndex
            selectedSegmentIndex = segmentIndex(at: touch.location(in: self))
            tick()
        }
        super.touchesBegan(touches, with: event)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        wakeDisplayLink()
        if let touch = touches.first, movesLensOnTouchDown {
            let index = segmentIndex(at: touch.location(in: self))
            if index != selectedSegmentIndex {
                selectedSegmentIndex = index
                tick()
            }
        }
        super.touchesMoved(touches, with: event)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        wakeDisplayLink()
        if movesLensOnTouchDown, let originalIndex {
            if selectedSegmentIndex != originalIndex {
                onSelect(selectedSegmentIndex)
            } else {
                onReselect(selectedSegmentIndex)
            }
        }
        originalIndex = nil
        isTrackingTouch = false
        super.touchesEnded(touches, with: event)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        wakeDisplayLink()
        if movesLensOnTouchDown, let originalIndex {
            selectedSegmentIndex = originalIndex
        }
        originalIndex = nil
        isTrackingTouch = false
        super.touchesCancelled(touches, with: event)
    }

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        onLongPress(segmentIndex(at: recognizer.location(in: self)))
    }
}

@available(iOS 26.0, *)
@MainActor
private final class NuvioDisplayLinkProxy: NSObject {
    weak var control: NuvioTabSegmentedControl?

    init(control: NuvioTabSegmentedControl) {
        self.control = control
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let control else {
            link.invalidate()
            return
        }
        control.updateAccentMasks()
    }
}

@available(iOS 26.0, *)
final class NuvioTabItemContentView: UIView {
    private let title: String
    private let image: UIImage
    private let tintsImage: Bool
    private let horizontal: Bool
    var drawsLegibilityShadow = false

    private static let iconSize: CGFloat = 24
    private static let iconArea: CGFloat = 28
    private let font = UIFont.systemFont(ofSize: 11, weight: .medium)

    init(title: String, image: UIImage, tintsImage: Bool, horizontal: Bool = false) {
        self.title = title
        self.image = image
        self.tintsImage = tintsImage
        self.horizontal = horizontal
        super.init(frame: .zero)
        isOpaque = false
        isUserInteractionEnabled = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) {
        title = ""
        image = UIImage()
        tintsImage = true
        horizontal = false
        super.init(coder: coder)
        isHidden = true
    }

    override func tintColorDidChange() {
        super.tintColorDidChange()
        setNeedsDisplay()
    }

    private static let horizontalGap: CGFloat = 6

    override var intrinsicContentSize: CGSize {
        let text = (title as NSString).size(withAttributes: [.font: font])
        if horizontal {
            return CGSize(
                width: Self.iconSize + Self.horizontalGap + ceil(text.width) + 4,
                height: max(Self.iconSize, ceil(text.height)) + 4
            )
        }
        return CGSize(width: ceil(max(Self.iconSize, text.width)) + 4, height: Self.iconArea + ceil(text.height))
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let color = tintColor ?? .white
        if drawsLegibilityShadow {
            context.setShadow(offset: .zero, blur: 3, color: UIColor.black.withAlphaComponent(0.35).cgColor)
        }

        let aspect = image.size.height > 0 ? image.size.width / image.size.height : 1
        let iconSize = aspect >= 1
            ? CGSize(width: Self.iconSize, height: Self.iconSize / aspect)
            : CGSize(width: Self.iconSize * aspect, height: Self.iconSize)
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        let textSize = (title as NSString).size(withAttributes: attributes)

        let iconRect: CGRect
        let textOrigin: CGPoint
        if horizontal {
            let contentWidth = Self.iconSize + Self.horizontalGap + textSize.width
            let startX = (bounds.width - contentWidth) / 2
            iconRect = CGRect(
                x: startX + (Self.iconSize - iconSize.width) / 2,
                y: (bounds.height - iconSize.height) / 2,
                width: iconSize.width,
                height: iconSize.height
            )
            textOrigin = CGPoint(
                x: startX + Self.iconSize + Self.horizontalGap,
                y: (bounds.height - textSize.height) / 2
            )
        } else {
            iconRect = CGRect(
                x: (bounds.width - iconSize.width) / 2,
                y: (Self.iconArea - iconSize.height) / 2 - 1,
                width: iconSize.width,
                height: iconSize.height
            )
            textOrigin = CGPoint(x: (bounds.width - textSize.width) / 2, y: Self.iconArea - 1)
        }

        if tintsImage {
            color.setFill()
            image.withRenderingMode(.alwaysTemplate).withTintColor(color).draw(in: iconRect)
        } else {
            image.draw(in: iconRect)
        }
        (title as NSString).draw(at: textOrigin, withAttributes: attributes)
    }
}
