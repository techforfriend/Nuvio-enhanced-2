import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Fixes punctuation placement in Hebrew external subtitles on iOS.
///
/// Many Hebrew SRT files (Wizdom, Ktuvit, older OpenSubtitles uploads) are authored
/// for renderers that lay every line out left-to-right: sentence-final punctuation is
/// stored at the *start* of the line (".זה היה מאוד מריר") so that it appears on the
/// visual left, i.e. at the end of the Hebrew sentence.
///
/// mpv converts SRT/VTT to ASS and, unless `sub-vsfilter-bidi-compat` is set, lets libass
/// resolve the base direction from the text (`Encoding = -1`). Hebrew lines are then laid
/// out right-to-left and the legacy leading punctuation is shown at the start of the
/// sentence instead of the end.
///
/// Android already handles this in `PlayerSubtitleRtlFix.kt`. This is the iOS
/// equivalent for external subtitles: the file is fetched off the main thread, and if it
/// is detected as a legacy-ordered Hebrew file, every Hebrew line has its leading and
/// trailing punctuation runs swapped (with bracket mirroring), producing correct logical
/// order for a bidi-aware renderer. Correctly authored files are left untouched.
enum MPVSubtitleRtlFix {

    struct Result: Equatable {
        let text: String
        let changedLines: Int
    }

    // MARK: - Detection

    /// Minimum number of Hebrew text lines before we trust the per-file statistics.
    static let minimumHebrewLines = 8

    /// A file is treated as legacy-ordered when Hebrew lines that start with
    /// punctuation clearly outnumber Hebrew lines that end with punctuation.
    static func isLegacyOrdered(leading: Int, trailing: Int, hebrewLines: Int) -> Bool {
        guard hebrewLines >= minimumHebrewLines else { return false }
        guard leading >= 3 else { return false }
        return leading >= max(3, trailing * 3)
    }

    // MARK: - Transform

    /// Returns the fixed subtitle text, or nil when the file should be passed to mpv as-is.
    static func fix(_ text: String) -> Result? {
        let format = SubtitleFormat.detect(text)
        guard format != .unsupported else { return nil }

        let newline = text.contains("\r\n") ? "\r\n" : "\n"
        let lines = text.components(separatedBy: newline)

        var leading = 0
        var trailing = 0
        var hebrewLines = 0
        var textLineIndexes: [Int] = []

        for (index, rawLine) in lines.enumerated() where format.isTextLine(rawLine) {
            let parts = TaggedLine(rawLine)
            guard containsHebrew(parts.core) else { continue }
            textLineIndexes.append(index)
            hebrewLines += 1
            if let first = parts.core.first, isLeadingPunctuation(first) { leading += 1 }
            if let last = parts.core.last, isTrailingPunctuation(last) { trailing += 1 }
        }

        guard isLegacyOrdered(leading: leading, trailing: trailing, hebrewLines: hebrewLines) else {
            return nil
        }

        var output = lines
        var changed = 0
        for index in textLineIndexes {
            let fixed = swapPunctuationRuns(in: lines[index])
            if fixed != lines[index] {
                output[index] = fixed
                changed += 1
            }
        }
        guard changed > 0 else { return nil }
        return Result(text: output.joined(separator: newline), changedLines: changed)
    }

    /// Moves the leading punctuation run to the end and the trailing run to the start,
    /// reversing each run and mirroring brackets. Formatting tags (`<i>`, `{\an8}`)
    /// around the text are kept in place.
    static func swapPunctuationRuns(in line: String) -> String {
        let parts = TaggedLine(line)
        let core = Array(parts.core)
        guard !core.isEmpty else { return line }

        var start = 0
        while start < core.count, isLeadingPunctuation(core[start]) || core[start].isWhitespace {
            start += 1
        }
        var end = core.count
        while end > start, isTrailingPunctuation(core[end - 1]) || core[end - 1].isWhitespace {
            end -= 1
        }
        if start == 0 && end == core.count { return line }
        if start >= end { return line }

        let leadingRun = String(core[0..<start].filter { !$0.isWhitespace }.reversed().map(mirror))
        let trailingRun = String(core[end..<core.count].filter { !$0.isWhitespace }.reversed().map(mirror))
        let middle = String(core[start..<end])
        return parts.prefix + trailingRun + middle + leadingRun + parts.suffix
    }

    // MARK: - Character classes

    private static let punctuation: Set<Character> = [
        ".", ",", "?", "!", "-", ":", ";", "\u{2026}", "(", ")", "'", "\""
    ]

    static func isLeadingPunctuation(_ c: Character) -> Bool { punctuation.contains(c) }
    static func isTrailingPunctuation(_ c: Character) -> Bool { punctuation.contains(c) }

    private static func mirror(_ c: Character) -> Character {
        switch c {
        case "(": return ")"
        case ")": return "("
        default: return c
        }
    }

    static func containsHebrew(_ text: String) -> Bool {
        text.unicodeScalars.contains { (0x0590...0x05FF).contains($0.value) || (0xFB1D...0xFB4F).contains($0.value) }
    }

    // MARK: - Decoding

    /// Decodes downloaded subtitle bytes. Returns nil for binary/compressed payloads.
    static func decode(_ data: Data) -> String? {
        if data.isEmpty { return nil }
        // gzip / zip payloads: let mpv handle them as before.
        if data.starts(with: [0x1F, 0x8B]) || data.starts(with: [0x50, 0x4B, 0x03, 0x04]) { return nil }
        var bytes = data
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { bytes = bytes.dropFirst(3) }
        if let utf8 = String(data: bytes, encoding: .utf8) { return utf8 }
        if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]) {
            return String(data: data, encoding: .utf16)
        }
        return String(data: bytes, encoding: windowsHebrew)
    }

    private static let windowsHebrew: String.Encoding = {
        #if canImport(Darwin)
        let cf = CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.windowsHebrew.rawValue))
        return String.Encoding(rawValue: cf)
        #else
        return .windowsCP1252 // Linux test builds only; Hebrew cp1255 is exercised on device.
        #endif
    }()
}

// MARK: - Download + cache

extension MPVSubtitleRtlFix {

    static let downloadTimeout: TimeInterval = 15
    static let maxSubtitleBytes = 8 * 1024 * 1024

    /// Downloads the subtitle off the main thread and, when it needs fixing, writes a
    /// corrected UTF-8 copy to the caches directory. Calls `completion` with the local
    /// file URL to load instead, or nil to keep using the original URL.
    static func prepare(
        url: String,
        headers: [String: String],
        completion: @escaping (_ localPath: String?, _ title: String) -> Void
    ) {
        let title = displayName(for: url)
        guard let remote = URL(string: url), let scheme = remote.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            completion(nil, title)
            return
        }

        var request = URLRequest(url: remote, timeoutInterval: downloadTimeout)
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }

        URLSession.shared.dataTask(with: request) { data, response, error in
            guard error == nil,
                  let data, data.count <= maxSubtitleBytes,
                  let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let text = decode(data),
                  let fixed = fix(text) else {
                completion(nil, title)
                return
            }
            do {
                let path = try writeFixedCopy(fixed.text, title: title)
                completion(path, title)
            } catch {
                completion(nil, title)
            }
        }.resume()
    }

    static func displayName(for url: String) -> String {
        let withoutQuery = url.split(separator: "?", maxSplits: 1).first.map(String.init) ?? url
        let last = withoutQuery.split(separator: "/").last.map(String.init) ?? withoutQuery
        return last.removingPercentEncoding ?? last
    }

    private static func writeFixedCopy(_ text: String, title: String) throws -> String {
        let fm = FileManager.default
        let base = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("rtl-subtitles", isDirectory: true)
        try fm.createDirectory(at: base, withIntermediateDirectories: true)
        purgeOldFiles(in: base)

        let safeTitle = title.replacingOccurrences(of: "/", with: "_")
        let ext = (safeTitle as NSString).pathExtension.isEmpty ? "srt" : (safeTitle as NSString).pathExtension
        let file = base.appendingPathComponent("\(UUID().uuidString).\(ext)")
        try text.write(to: file, atomically: true, encoding: .utf8)
        return file.path
    }

    private static func purgeOldFiles(in directory: URL) {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey]
        ) else { return }
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        for item in items {
            let modified = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
            if let modified, modified < cutoff { try? fm.removeItem(at: item) }
        }
    }
}

// MARK: - Helpers

private enum SubtitleFormat {
    case srt, vtt, unsupported

    static func detect(_ text: String) -> SubtitleFormat {
        let head = text.prefix(2048)
        if head.hasPrefix("WEBVTT") { return .vtt }
        if head.contains("[Script Info]") || head.contains("[Events]") { return .unsupported }
        if head.contains("-->") { return .srt }
        return .unsupported
    }

    func isTextLine(_ line: String) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return false }
        if trimmed.contains("-->") { return false }
        if trimmed.allSatisfy(\.isNumber) { return false }
        if self == .vtt, trimmed.hasPrefix("WEBVTT") || trimmed.hasPrefix("NOTE") || trimmed.hasPrefix("STYLE") {
            return false
        }
        return true
    }
}

/// Splits a subtitle line into leading tags, text core and trailing tags.
private struct TaggedLine {
    let prefix: String
    let core: String
    let suffix: String

    init(_ line: String) {
        var chars = Array(line)
        var suffixChars: [Character] = []
        if chars.last == "\r" { suffixChars.insert(chars.removeLast(), at: 0) }

        var prefixEnd = 0
        while prefixEnd < chars.count, let close = TaggedLine.tagEnd(chars, from: prefixEnd) {
            prefixEnd = close + 1
        }
        var suffixStart = chars.count
        while suffixStart > prefixEnd, let open = TaggedLine.tagStart(chars, endingAt: suffixStart - 1), open >= prefixEnd {
            suffixStart = open
        }
        prefix = String(chars[0..<prefixEnd])
        core = String(chars[prefixEnd..<suffixStart])
        suffix = String(chars[suffixStart..<chars.count]) + String(suffixChars)
    }

    private static func tagEnd(_ chars: [Character], from index: Int) -> Int? {
        guard index < chars.count else { return nil }
        let close: Character
        switch chars[index] {
        case "<": close = ">"
        case "{": close = "}"
        default: return nil
        }
        var i = index + 1
        while i < chars.count {
            if chars[i] == close { return i }
            i += 1
        }
        return nil
    }

    private static func tagStart(_ chars: [Character], endingAt index: Int) -> Int? {
        guard index >= 0, index < chars.count else { return nil }
        let open: Character
        switch chars[index] {
        case ">": open = "<"
        case "}": open = "{"
        default: return nil
        }
        var i = index - 1
        while i >= 0 {
            if chars[i] == open { return i }
            i -= 1
        }
        return nil
    }
}
