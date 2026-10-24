import ObjectiveC
import UIKit

enum NuvioNavigationBarPassthrough {
    static func setEnabled(_ enabled: Bool, on navigationController: UINavigationController?) {
        guard let bar = navigationController?.navigationBar else { return }

        if enabled {
            _ = installHitTestOverride
        }

        bar.nuvioPassesThroughEmptyAreas = enabled
    }

    private static let installHitTestOverride: Void = {
        let original = #selector(UIView.hitTest(_:with:))
        let replacement = #selector(UINavigationBar.nuvio_hitTest(_:with:))

        guard
            let originalMethod = class_getInstanceMethod(UINavigationBar.self, original),
            let replacementMethod = class_getInstanceMethod(UINavigationBar.self, replacement)
        else { return }

        let addedToNavigationBar = class_addMethod(
            UINavigationBar.self,
            original,
            method_getImplementation(replacementMethod),
            method_getTypeEncoding(replacementMethod)
        )

        if addedToNavigationBar {
            class_replaceMethod(
                UINavigationBar.self,
                replacement,
                method_getImplementation(originalMethod),
                method_getTypeEncoding(originalMethod)
            )
        } else {
            method_exchangeImplementations(originalMethod, replacementMethod)
        }
    }()
}

private let nuvioPassthroughAssociationKey = UnsafeRawPointer(
    UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1)
)

extension UINavigationBar {
    fileprivate var nuvioPassesThroughEmptyAreas: Bool {
        get { objc_getAssociatedObject(self, nuvioPassthroughAssociationKey) as? Bool ?? false }
        set {
            objc_setAssociatedObject(
                self,
                nuvioPassthroughAssociationKey,
                newValue,
                .OBJC_ASSOCIATION_RETAIN_NONATOMIC
            )
        }
    }

    @objc dynamic fileprivate func nuvio_hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let hit = nuvio_hitTest(point, with: event) else { return nil }
        guard nuvioPassesThroughEmptyAreas else { return hit }

        var view: UIView? = hit
        while let current = view, current !== self {
            if current is UIControl {
                return hit
            }
            view = current.superview
        }

        return nil
    }
}
