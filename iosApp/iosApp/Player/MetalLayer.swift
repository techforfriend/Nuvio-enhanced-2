import Foundation
import UIKit
import QuartzCore
import CoreVideo
import Metal

class MetalLayer: CAMetalLayer {
    weak var pictureInPictureSink: PlayerPictureInPictureController?
    private let pictureInPictureLock = NSLock()
    private var consumesVideo = false
    private var presentationGeneration: UInt = 0
    private var presentationObserver: (generation: UInt, callback: (TimeInterval) -> Void)?

    func observeNextPresentedDrawable(_ callback: @escaping (TimeInterval) -> Void) {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = (presentationGeneration, callback)
        pictureInPictureLock.unlock()
    }

    func cancelPresentationObserver() {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = nil
        pictureInPictureLock.unlock()
    }

    private func attachPresentationObserver(to drawable: CAMetalDrawable, generation: UInt?) {
        guard let generation else { return }
        pictureInPictureLock.lock()
        let observer = presentationObserver?.generation == generation ? presentationObserver : nil
        if observer != nil { presentationObserver = nil }
        pictureInPictureLock.unlock()
        guard let observer else { return }
        drawable.addPresentedHandler { [weak self] presented in
            let time = presented.presentedTime
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.pictureInPictureLock.lock()
                let current = self.presentationGeneration == generation
                self.pictureInPictureLock.unlock()
                if current { observer.callback(time) }
            }
        }
    }

    @objc var nuvioPiPConsumesVideo: Bool {
        get {
            pictureInPictureLock.lock()
            defer { pictureInPictureLock.unlock() }
            return consumesVideo
        }
        set {
            pictureInPictureLock.lock()
            consumesVideo = newValue
            pictureInPictureLock.unlock()
        }
    }

    @objc func nuvioEnqueuePiPFrame(_ pixelBuffer: CVPixelBuffer, presentationTime: Double, isPictureInPictureFrame: Bool) {
        pictureInPictureSink?.submit(pixelBuffer, pts: presentationTime, isPictureInPictureFrame: isPictureInPictureFrame)
    }

    var onDrawablePresented: ((CAMetalDrawable) -> Void)?

    var onRenderingSuspensionChanged: ((Bool, String) -> Void)?

    var isDrawableCaptureArmed: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return storedIsDrawableCaptureArmed
        }
        set {
            captureLock.lock()
            storedIsDrawableCaptureArmed = newValue
            captureLock.unlock()
        }
    }

    var capturesWithoutPresentation: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return storedCapturesWithoutPresentation
        }
        set {
            captureLock.lock()
            storedCapturesWithoutPresentation = newValue
            captureLock.unlock()
        }
    }

    private(set) var capturedDrawableCount: UInt64 = 0

    private(set) var nextDrawableCallCount: UInt64 = 0

    private var storedIsDrawableCaptureArmed = false
    private var storedCapturesWithoutPresentation = false
    private var pendingDrawable: CAMetalDrawable?
    private let captureLock = NSLock()

    private static let failureThresholdBeforeSuspension = 2
    private static let suspendedRetryInterval: CFTimeInterval = 1.0
    private static let suspendedIdleSleep: TimeInterval = 0.03

    private var isRenderingSuspended = false
    private var isSuspensionLatched = false
    private var consecutiveAcquisitionFailures = 0
    private var lastSuspendedProbeTime: CFTimeInterval = 0

    var isSuspended: Bool {
        captureLock.lock()
        defer { captureLock.unlock() }
        return isRenderingSuspended
    }

    override var drawableSize: CGSize {
        get { return super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }

    override var wantsExtendedDynamicRangeContent: Bool {
        get { return super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.async {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }

    func setRenderingSuspended(_ suspended: Bool, reason: String) {
        captureLock.lock()
        let changed = isRenderingSuspended != suspended
        isRenderingSuspended = suspended
        isSuspensionLatched = suspended
        consecutiveAcquisitionFailures = 0
        lastSuspendedProbeTime = suspended ? CACurrentMediaTime() : 0
        let stale = pendingDrawable
        pendingDrawable = nil
        captureLock.unlock()

        withExtendedLifetime(stale) {}

        if changed { onRenderingSuspensionChanged?(suspended, reason) }
    }

    func releasePendingDrawable() {
        captureLock.lock()
        let stale = pendingDrawable
        pendingDrawable = nil
        captureLock.unlock()
        withExtendedLifetime(stale) {}
    }

    override func nextDrawable() -> CAMetalDrawable? {
        captureLock.lock()
        nextDrawableCallCount &+= 1
        if isRenderingSuspended {
            let now = CACurrentMediaTime()
            let shouldProbe = now - lastSuspendedProbeTime >= Self.suspendedRetryInterval
            if shouldProbe {
                lastSuspendedProbeTime = now
            }
            let stale = pendingDrawable
            pendingDrawable = nil
            captureLock.unlock()
            withExtendedLifetime(stale) {}

            guard shouldProbe else {
                Thread.sleep(forTimeInterval: Self.suspendedIdleSleep)
                return nil
            }
        } else {
            captureLock.unlock()
        }

        pictureInPictureLock.lock()
        let observerGeneration = presentationObserver?.generation
        pictureInPictureLock.unlock()
        let drawable = super.nextDrawable()
        if let drawable { attachPresentationObserver(to: drawable, generation: observerGeneration) }
        let acquisitionFailed = drawable == nil

        captureLock.lock()
        var didSuspend = false
        var didResume = false
        if acquisitionFailed {
            consecutiveAcquisitionFailures += 1
            if !isRenderingSuspended &&
                consecutiveAcquisitionFailures >= Self.failureThresholdBeforeSuspension {
                isRenderingSuspended = true
                lastSuspendedProbeTime = CACurrentMediaTime()
                didSuspend = true
            }
        } else {
            consecutiveAcquisitionFailures = 0
            if isRenderingSuspended && !isSuspensionLatched {
                isRenderingSuspended = false
                lastSuspendedProbeTime = 0
                didResume = true
            }
        }

        let suspendedNow = isRenderingSuspended
        let armed = storedIsDrawableCaptureArmed
        let handler = onDrawablePresented
        let deferred = storedCapturesWithoutPresentation && !suspendedNow
        let previous = pendingDrawable
        pendingDrawable = deferred ? drawable : nil
        captureLock.unlock()

        if didSuspend { onRenderingSuspensionChanged?(true, "drawable-acquisition-stalled") }
        if didResume { onRenderingSuspensionChanged?(false, "drawable-acquired") }

        guard !suspendedNow else { return drawable }

        guard armed, let drawable, let handler else { return drawable }

        if deferred {
            if let previous {
                captureLock.lock()
                capturedDrawableCount &+= 1
                captureLock.unlock()
                handler(previous)
            }
            return drawable
        }

        drawable.addPresentedHandler { [weak self] _ in
            guard let self else { return }
            self.captureLock.lock()
            self.capturedDrawableCount &+= 1
            self.captureLock.unlock()
            handler(drawable)
        }

        return drawable
    }
}
