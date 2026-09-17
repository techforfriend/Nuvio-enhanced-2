import ComposeApp
import CoreText
import Foundation
import Libmpv
import UIKit

// MARK: - Resolver


enum MPVSubtitleFontResolver {

    /// Set to true to log a full per-font report at startup. Everything it prints
    /// goes through InAppLogBridge directly, so it does not depend on mpv's log
    /// level. Useful again if a future iOS moves fonts around.
    static var diagnosticsEnabled = false


    // MARK: Scripts

    enum Script: String, CaseIterable {
        case latinExtended, han, japanese, korean, thai, arabic, hebrew, devanagari

        var systemFamilies: [String] {
            switch self {
            case .latinExtended:
                return ["Noto Sans"]
            case .han:
                return ["PingFang SC", "PingFang TC", "Hiragino Sans"]
            case .japanese:
                return ["Hiragino Sans", "Hiragino Maru Gothic ProN"]
            case .korean:
                return ["Apple SD Gothic Neo", "AppleGothic"]
            case .thai:
                return ["Thonburi", "Sukhumvit Set"]
            case .arabic:
                return ["Geeza Pro", "Al Nile", "Damascus"]
            case .hebrew:
                return ["Arial Hebrew"]
            case .devanagari:
                return ["Kohinoor Devanagari", "Devanagari Sangam MN"]
            }
        }

        var bundledFamilies: [String] { MPVSubtitleFontResolver.registeredFamilies }

        var candidateFamilies: [String] {
            self == .han || self == .latinExtended
                ? bundledFamilies + systemFamilies
                : systemFamilies + bundledFamilies
        }

        var probeScalars: [UnicodeScalar] {
            switch self {
            case .latinExtended:
                return [
                    "\u{011E}", "\u{011F}", "\u{0130}", "\u{0131}", "\u{015E}", "\u{015F}",
                    "\u{0416}", "\u{0436}", "\u{042F}", "\u{044F}",
                    "\u{03A9}", "\u{03C9}", "\u{0386}", "\u{03AC}"
                ]
            case .han:        return ["\u{4E2D}", "\u{4EEC}", "\u{8FD9}"]
            case .japanese:   return ["\u{3042}", "\u{6F22}"]
            case .korean:     return ["\u{AC00}"]
            case .thai:       return ["\u{0E01}"]
            case .arabic:     return ["\u{0627}"]
            case .hebrew:     return ["\u{05D0}"]
            case .devanagari: return ["\u{0915}"]
            }
        }
    }

    // MARK: Bundled fonts

    private static let bundledFontsDirectory = "SubtitleFonts"

    @discardableResult
    static func registerBundledFonts() -> [String] {
        registrationLock.lock()
        defer { registrationLock.unlock() }

        if let cached = registrationLog { return cached }

        var log: [String] = []
        var families: [String] = []
        var urls: [URL] = []

        for ext in ["otf", "ttf", "ttc", "otc"] {
            urls += Bundle.main.urls(forResourcesWithExtension: ext, subdirectory: bundledFontsDirectory) ?? []
            urls += Bundle.main.urls(forResourcesWithExtension: ext, subdirectory: nil) ?? []
        }

        for url in Set(urls) {
            var error: Unmanaged<CFError>?
            guard CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) else {
                log.append("FAILED \(url.lastPathComponent): \(error?.takeRetainedValue().localizedDescription ?? "unknown")")
                continue
            }

            let discovered = (CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor])?
                .compactMap { CTFontDescriptorCopyAttribute($0, kCTFontFamilyNameAttribute) as? String } ?? []

            families += discovered
            log.append("registered \(url.lastPathComponent) families=\(Set(discovered).sorted().joined(separator: "/"))")
        }

        registeredFamilies = Set(families).sorted()
        if log.isEmpty { log.append("no bundled subtitle fonts found (system fonts only)") }

        registrationLog = log
        return log
    }

    private static let registrationLock = NSLock()
    private static var registrationLog: [String]?

    private(set) static var registeredFamilies: [String] = []

    // MARK: Resolution

    /// Use the bundled Latin face for every script that does not need a
    /// script-specific fallback. This keeps Turkish and other Latin subtitles
    /// visually consistent without making the workaround Turkish-specific.
    /// Keep mpv's default as a safe fallback if registration ever fails.
    static var defaultFamily: String {
        registeredFamilies.first {
            $0.caseInsensitiveCompare("Noto Sans") == .orderedSame
        } ?? "sans-serif"
    }

    /// Keep the bundled CJK face as Enhanced's startup/baseline subtitle font.
    static var baselineFamily: String? { family(for: .han) }

    static func family(for script: Script) -> String? {
        cacheLock.lock()
        defer { cacheLock.unlock() }

        if let cached = cache[script] { return cached }
        let resolved = script.candidateFamilies.first { isUsable($0, for: script) }
        cache[script] = .some(resolved)
        return resolved
    }

    static func script(forLanguageTag tag: String?) -> Script? {
        guard let tag, !tag.isEmpty else { return nil }

        let normalized = tag.lowercased().replacingOccurrences(of: "_", with: "-")
        let base = normalized.split(separator: "-").first.map(String.init) ?? normalized

        // The subtitle provider's language tag is the preferred track-level
        // hint. Keep ordinary Latin, Greek, and Cyrillic languages on the
        // bundled Latin face; this is one generic script rule, not a
        // Turkish-only exception.
        if [
            "tr", "tur", "turkish", "az", "aze", "azerbaijani", "uz", "uzb", "uzbek", "crh", "zza", "lzz",
            "en", "eng", "english", "de", "deu", "ger", "german", "fr", "fra", "fre", "french", "es", "spa", "spanish",
            "it", "ita", "italian", "pt", "por", "portuguese", "nl", "nld", "dut", "dutch", "da", "dan", "danish", "sv", "swe", "swedish",
            "no", "nor", "norwegian", "nb", "nn", "fin", "fi", "finnish", "isl", "is", "icelandic", "pl", "pol", "polish", "cs", "ces",
            "cze", "czech", "sk", "slk", "slo", "slovak", "hr", "hrv", "croatian", "ro", "ron", "rum", "romanian", "hu", "hun", "hungarian"
        ].contains(base) {
            return .latinExtended
        }

        switch base {
        case "el", "ell", "gre", "gr", "greek", "greek-modern", "modern-greek": return .latinExtended
        case "ru", "rus", "uk", "ukr", "bg", "bul", "sr", "srp",
             "mk", "mkd", "be", "bel", "kk", "kaz", "ky", "kir",
             "tg", "tgk", "mn", "mon", "tt", "tat", "ba", "bak",
             "cv", "chv", "os", "oss", "ab", "abk", "ce", "che",
             "russian", "ukrainian", "bulgarian", "serbian", "macedonian", "belarusian": return .latinExtended
        case "zh", "zho", "chi", "cmn", "yue", "nan", "hak", "chinese", "mandarin", "cantonese": return .han
        case "ja", "jpn", "jp", "japanese":                                             return .japanese
        case "ko", "kor", "korean":                                                       return .korean
        case "th", "tha", "thai":                                                         return .thai
        case "ar", "ara", "fa", "fas", "per", "ur", "urd", "ps", "pus", "ku", "arabic", "persian", "urdu": return .arabic
        case "he", "heb", "iw", "yi", "yid", "hebrew", "yiddish":                     return .hebrew
        case "hi", "hin", "mr", "mar", "ne", "nep", "sa", "san", "hindi", "marathi", "nepali", "sanskrit": return .devanagari
        default:                                                          return nil
        }
    }

    static func script(forText text: String) -> Script? {
        var counts: [Script: Int] = [:]
        for scalar in text.unicodeScalars {
            guard let script = script(forScalar: scalar) else { continue }
            counts[script, default: 0] += 1
        }
        return counts.max { $0.value < $1.value }?.key
    }

    /// Return script evidence for one currently displayed cue. The controller
    /// keeps only aggregate integer counts, never the old cue strings.
    static func scriptScores(forText text: String) -> [Script: Int] {
        var counts: [Script: Int] = [:]
        for scalar in text.unicodeScalars {
            guard let script = script(forScalar: scalar) else { continue }
            counts[script, default: 0] += 1
        }

        // Japanese naturally mixes kana and Han. Treat both as one Japanese
        // profile so a kanji-heavy cue cannot flip the family to Chinese.
        if let japanese = counts[.japanese], let han = counts[.han] {
            counts[.japanese] = japanese + han
            counts[.han] = nil
        }
        return counts
    }

    private static func script(forScalar scalar: UnicodeScalar) -> Script? {
        switch scalar.value {
        case 0x00C0...0x024F, 0x1E00...0x1EFF,
             0x2C60...0x2C7F, 0xA720...0xA7FF,
             0x0370...0x03FF, 0x1F00...0x1FFF,
             0x0400...0x052F:                                      return .latinExtended
        case 0x3040...0x30FF, 0x31F0...0x31FF:                       return .japanese
        case 0x3400...0x4DBF, 0x4E00...0x9FFF,
             0xF900...0xFAFF, 0x20000...0x2FA1F:                     return .han
        case 0x1100...0x11FF, 0x3130...0x318F, 0xAC00...0xD7AF:      return .korean
        case 0x0E00...0x0E7F:                                        return .thai
        case 0x0600...0x06FF, 0x0750...0x077F,
             0xFB50...0xFDFF, 0xFE70...0xFEFF:                       return .arabic
        case 0x0590...0x05FF:                                        return .hebrew
        case 0x0900...0x097F:                                        return .devanagari
        default:                                                     return nil
        }
    }

    // MARK: Probing

    private static let cacheLock = NSLock()
    private static var cache: [Script: String?] = [:]

    private static func isUsable(_ family: String, for script: Script) -> Bool {
        let font = CTFontCreateWithName(family as CFString, 12.0, nil)

        guard (CTFontCopyFamilyName(font) as String).caseInsensitiveCompare(family) == .orderedSame else {
            return false
        }

        guard script.probeScalars.allSatisfy({ hasGlyph(font, $0) }) else { return false }

        return isLoadableByFreeType(font)
    }

    private static func isLoadableByFreeType(_ font: CTFont) -> Bool {
        let descriptor = CTFontCopyFontDescriptor(font)
        guard let url = CTFontDescriptorCopyAttribute(descriptor, kCTFontURLAttribute) as? URL,
              FileManager.default.isReadableFile(atPath: url.path) else {
            return false
        }
        return !url.path.hasPrefix("/System/Library/PrivateFrameworks/")
    }

    private static func hasGlyph(_ font: CTFont, _ scalar: UnicodeScalar) -> Bool {
        var utf16 = Array(String(scalar).utf16)
        var glyphs = [CGGlyph](repeating: 0, count: utf16.count)
        guard CTFontGetGlyphsForCharacters(font, &utf16, &glyphs, utf16.count) else { return false }
        return glyphs.allSatisfy { $0 != 0 }
    }

    // MARK: Diagnostics

    static func diagnosticsReport() -> [String] {
        var lines = ["bundled families: \(registeredFamilies.isEmpty ? "(none)" : registeredFamilies.joined(separator: ", "))"]

        for script in Script.allCases {
            for family in script.candidateFamilies {
                let font = CTFontCreateWithName(family as CFString, 12.0, nil)
                let resolved = CTFontCopyFamilyName(font) as String
                let nameOK = resolved.caseInsensitiveCompare(family) == .orderedSame
                let glyphs = script.probeScalars
                    .map { String(format: "U+%04X=%@", $0.value, hasGlyph(font, $0) ? "y" : "n") }
                    .joined(separator: ",")
                let url = CTFontDescriptorCopyAttribute(CTFontCopyFontDescriptor(font), kCTFontURLAttribute) as? URL

                lines.append(
                    "\(script.rawValue)/\(family): \(isUsable(family, for: script) ? "USABLE" : "rejected") "
                    + "name=\(nameOK ? "ok" : "SUBSTITUTED(\(resolved))") "
                    + "glyphs=[\(glyphs)] url=\(url?.path ?? "NIL")"
                )
            }
        }
        return lines
    }
}

// MARK: - Player wiring

final class MPVSubtitleFontController {

    private weak var player: MPVPlayerViewController?

    private var appliedFamily: String?
    private var scriptFromLanguage: MPVSubtitleFontResolver.Script?
    private var scriptFromText: MPVSubtitleFontResolver.Script?
    private var provisionalScript: MPVSubtitleFontResolver.Script?
    private var scoreTotals: [MPVSubtitleFontResolver.Script: Int] = [:]
    private var informativeCueCount = 0
    private var profileLocked = false
    private var currentSubtitleTrackID: Int64?

    private let minimumEvidenceCues = 3
    private let maximumEvidenceCues = 5
    private let confidenceThreshold = 65.0
    private let confidenceMargin = 15.0

    init(player: MPVPlayerViewController) {
        self.player = player
    }

    func applySetupOptions(_ setOption: (String, String) -> Void) {
        for line in MPVSubtitleFontResolver.registerBundledFonts() {
            InAppLogBridge.shared.info(tag: "MPV/SubFont", message: line)
        }

        if MPVSubtitleFontResolver.diagnosticsEnabled {
            for line in MPVSubtitleFontResolver.diagnosticsReport() {
                InAppLogBridge.shared.info(tag: "MPV/SubFont", message: line)
            }
        }

        guard let family = MPVSubtitleFontResolver.baselineFamily else {
            InAppLogBridge.shared.warn(
                tag: "MPV/SubFont",
                message: "No CJK-capable font available; non-Latin subtitles may render as boxes"
            )
            return
        }

        // Preserve Enhanced's original baseline: the bundled CJK font is
        // selected once at startup. A language tag can immediately select a
        // more suitable family; an untagged track is profiled from the first
        // few non-empty subtitle cues and then locked.
        setOption("sub-font", family)
        appliedFamily = family
        InAppLogBridge.shared.info(tag: "MPV/SubFont", message: "baseline font: \(family)")
    }

    func reapplyFont() {
        guard let family = appliedFamily else { return }
        player?.setStringProperty("sub-font", family)
    }

    func handlePropertyChange(_ eventPtr: UnsafeMutablePointer<mpv_event>) {
        guard let data = eventPtr.pointee.data else { return }
        let property = UnsafePointer<mpv_event_property>(OpaquePointer(data)).pointee
        guard let namePtr = property.name else { return }

        let name = String(cString: namePtr)
        guard name == "sub-text"
                || name == "current-tracks/sub/lang"
                || name == "current-tracks/sub/id" else { return }

        if name == "current-tracks/sub/id" {
            guard property.format == MPV_FORMAT_INT64, let valueData = property.data else { return }
            let id = valueData.assumingMemoryBound(to: Int64.self).pointee
            DispatchQueue.main.async { [weak self] in
                self?.handleTrackID(id >= 0 ? id : nil)
            }
            return
        }

        var value: String?
        if property.format == MPV_FORMAT_STRING, let valueData = property.data {
            value = valueData.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee
                .map { String(cString: $0) }
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if name == "sub-text" {
                self.handleText(value)
            } else {
                self.handleLanguage(value)
            }
        }
    }

    private func handleLanguage(_ tag: String?) {
        scriptFromLanguage = MPVSubtitleFontResolver.script(forLanguageTag: tag)
        resetTextProfile()
        applyResolvedFont()
    }

    private func handleTrackID(_ id: Int64?) {
        guard id != currentSubtitleTrackID else { return }

        currentSubtitleTrackID = id

        // mpv can deliver the language property just before or just after the
        // track-id property. Do not clear the language hint here: doing so can
        // erase the newly selected track's route when the two notifications
        // arrive in the opposite order. A real language change (including a
        // nil/unknown tag) is handled by handleLanguage and resets the profile.
        resetTextProfile()
        applyResolvedFont()
    }

    private func resetTextProfile() {
        scriptFromText = nil
        provisionalScript = nil
        scoreTotals.removeAll(keepingCapacity: true)
        informativeCueCount = 0
        profileLocked = scriptFromLanguage != nil
    }

    private func handleText(_ text: String?) {
        // A trusted language tag locks the track immediately. This prevents
        // every cue from reclassifying a mixed-script subtitle.
        guard !profileLocked,
              let text,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        let cueScores = MPVSubtitleFontResolver.scriptScores(forText: text)
        guard !cueScores.isEmpty else { return }

        informativeCueCount += 1
        for (script, count) in cueScores {
            scoreTotals[script, default: 0] += count
        }
        mergeJapaneseHanScores()

        // Keep only numeric evidence; the old cue text is discarded after
        // this event has been scored.
        if provisionalScript == nil, let provisional = bestScoredScript() {
            provisionalScript = provisional
            scriptFromText = provisional
            applyResolvedFont()
            InAppLogBridge.shared.info(
                tag: "MPV/SubFont",
                message: "provisional font -> \(appliedFamily ?? "unknown") (script=\(provisional.rawValue))"
            )
        }

        let ordered = scoreTotals.sorted { $0.value > $1.value }
        let total = scoreTotals.values.reduce(0, +)
        let topScore = total > 0 && !ordered.isEmpty ? Double(ordered[0].value) * 100.0 / Double(total) : 0.0
        let secondScore = total > 0 && ordered.count > 1 ? Double(ordered[1].value) * 100.0 / Double(total) : 0.0
        let confident = informativeCueCount >= minimumEvidenceCues
            && topScore >= confidenceThreshold
            && topScore - secondScore >= confidenceMargin

        if confident || informativeCueCount >= maximumEvidenceCues {
            finalizeProfile()
        }
    }

    private func mergeJapaneseHanScores() {
        guard let japanese = scoreTotals[.japanese], let han = scoreTotals[.han] else { return }
        scoreTotals[.japanese] = japanese + han
        scoreTotals[.han] = nil
    }

    private func bestScoredScript() -> MPVSubtitleFontResolver.Script? {
        scoreTotals.max { lhs, rhs in lhs.value < rhs.value }?.key
    }

    private func finalizeProfile() {
        guard !profileLocked else { return }

        if let chosen = bestScoredScript() {
            scriptFromText = chosen
            applyResolvedFont()
            let total = scoreTotals.values.reduce(0, +)
            let score = total > 0 ? Double(scoreTotals[chosen, default: 0]) * 100.0 / Double(total) : 0.0
            InAppLogBridge.shared.info(
                tag: "MPV/SubFont",
                message: "final font lock -> \(appliedFamily ?? "unknown") (script=\(chosen.rawValue), score=\(String(format: "%.1f", score)))"
            )
        }
        profileLocked = true
    }

    private func applyResolvedFont() {
        let script = scriptFromText ?? scriptFromLanguage
        let family = script.flatMap { MPVSubtitleFontResolver.family(for: $0) }
            ?? MPVSubtitleFontResolver.baselineFamily

        guard let family, family != appliedFamily else { return }

        appliedFamily = family
        player?.setStringProperty("sub-font", family)
        InAppLogBridge.shared.info(
            tag: "MPV/SubFont",
            message: "font -> \(family) (script=\(script?.rawValue ?? "default"))"
        )
    }
}
