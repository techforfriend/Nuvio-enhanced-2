import ObjectiveC
import UIKit

final class NuvioPassthroughNavigationBar: UINavigationBar {
    var passesThroughEmptyAreas = false

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let hit = super.hitTest(point, with: event) else { return nil }
        guard passesThroughEmptyAreas else { return hit }

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

enum NuvioNavigationBarPassthrough {
    static func setEnabled(_ enabled: Bool, on navigationController: UINavigationController?) {
        guard let bar = navigationController?.navigationBar else { return }

        if let bar = bar as? NuvioPassthroughNavigationBar {
            bar.passesThroughEmptyAreas = enabled
            return
        }

        guard enabled else { return }
        object_setClass(bar, NuvioPassthroughNavigationBar.self)
        (bar as? NuvioPassthroughNavigationBar)?.passesThroughEmptyAreas = true
    }
}
