package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.updater.VersionUtils

private val conventionalCommitPattern = Regex(
    """^(feat|fix|perf|refactor|docs|test|ci|build|chore|revert|i18n)(?:\(([^)]*)\))?(?:!)?(?::\s*|\s+).+$""",
    RegexOption.IGNORE_CASE,
)
private val authorSuffixPattern = Regex("""\s+@([A-Za-z0-9_.-]+?)[.!?,;:]?\s*$""")
private val markdownLinkPattern = Regex("""\[([^\]]+)]\([^)]*\)""")
private val htmlLineBreakPattern = Regex(
    """(?i)<(?:br\s*/?|/?(?:p|div|ul|ol)\b[^>]*>)""",
)
private val htmlListItemPattern = Regex("""(?i)<li\b[^>]*>""")
private val htmlListItemEndPattern = Regex("""(?i)</li>""")
private val htmlFormattingTagPattern = Regex(
    """(?i)</?(?:a|b|strong|i|em|code|del|s|u|small|sub|sup)\b[^>]*>""",
)
private val markdownHeadingPattern = Regex("""#{1,6}\s+.*""")
private val markdownSectionHeadingPattern = Regex(
    """^#{1,6}\s+(.+)$""",
    RegexOption.IGNORE_CASE,
)
private val boldSectionHeadingPattern = Regex(
    """^\*\*(.+?):\*\*\s*$""",
    RegexOption.IGNORE_CASE,
)
private val changeSectionPattern = Regex(
    """^(?:what(?:'|’)?s changed|changes?|changelog|release notes?|added features?|features?|bug fixes?|fixes?|improvements?|performance|localization|other changes?)$""",
    RegexOption.IGNORE_CASE,
)
private val ignoredSectionPattern = Regex(
    """^(?:variants?|downloads?|assets?|installation|notes?|contributors?|credits?)$""",
    RegexOption.IGNORE_CASE,
)
private val markdownBlockquotePattern = Regex("""^\s*>+\s?""")
private val markdownInlineCodePattern = Regex("""`+([^`]+)`+""")
private val markdownStrikePattern = Regex("""~~(.+?)~~""")
private val markdownTaskPattern = Regex("""^\[[ xX]\]\s+""")
private val markdownRulePattern = Regex("""-{3,}\s*$""")
private val listMarkerPattern = Regex("""^[•*+-]\s+""")
private val conventionalCommitDisplayPattern = Regex(
    """^(?:feat|fix|perf|refactor|docs|test|ci|build|chore|revert|i18n)(?:\([^)]*\))?(?:!)?:\s*""",
    RegexOption.IGNORE_CASE,
)
private val leadingAddPattern = Regex("""^add\b""", RegexOption.IGNORE_CASE)
private val iosWordPattern = Regex("""\bios\b""", RegexOption.IGNORE_CASE)
private val androidWordPattern = Regex("""\bandroid\b""", RegexOption.IGNORE_CASE)
private val tvWordPattern = Regex("""\btv\b""", RegexOption.IGNORE_CASE)
private val imdbWordPattern = Regex("""\bimdb\b""", RegexOption.IGNORE_CASE)
private val tmdbWordPattern = Regex("""\btmdb\b""", RegexOption.IGNORE_CASE)

private enum class ReleaseNoteSectionMode {
    NORMAL,
    CHANGES,
    IGNORE,
}

internal object WhatsNewSnapshotBuilder {
    fun build(
        releases: List<WhatsNewRelease>,
        currentVersion: String,
    ): WhatsNewSnapshot {
        val normalizedReleases = releases
            .mapNotNull { release ->
                VersionUtils.parse(release.version)?.let { version ->
                    version to release.copy(version = VersionUtils.normalize(release.version))
                }
            }
            .sortedByDescending { it.first }
            .distinctBy { it.first }
            .map { it.second }

        val current = VersionUtils.parse(currentVersion)
        val currentIndex = current?.let { target ->
            normalizedReleases.indexOfFirst { release ->
                VersionUtils.parse(release.version) == target
            }
        } ?: -1

        return if (currentIndex >= 0) {
            WhatsNewSnapshot(
                releases = normalizedReleases,
                currentVersion = VersionUtils.normalize(currentVersion),
                sinceYourVersion = normalizedReleases.take(currentIndex),
                hasCompleteSinceVersion = true,
            )
        } else {
            WhatsNewSnapshot(
                releases = normalizedReleases,
                currentVersion = VersionUtils.normalize(currentVersion),
                sinceYourVersion = emptyList(),
                hasCompleteSinceVersion = false,
            )
        }
    }

    fun cleanReleaseNotes(raw: String): List<WhatsNewNote> {
        val lines = raw
            .replace(htmlLineBreakPattern, "\n")
            .replace(htmlListItemPattern, "\n• ")
            .replace(htmlListItemEndPattern, "\n")
            .replace(markdownLinkPattern, "$1")
            .replace(htmlFormattingTagPattern, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\r", "")
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)

        var sectionMode = ReleaseNoteSectionMode.NORMAL
        val hasStructuredChangeSections = lines.any { line ->
            val structuralCandidate = line.replaceFirst(markdownBlockquotePattern, "").trim()
            val heading =
                markdownSectionHeadingPattern.matchEntire(structuralCandidate)?.groupValues?.getOrNull(1)
                    ?: boldSectionHeadingPattern.matchEntire(structuralCandidate)?.groupValues?.getOrNull(1)
            heading?.let(changeSectionPattern::matches) == true
        }

        return lines.mapNotNull { line ->
            val structuralCandidate = line.replaceFirst(markdownBlockquotePattern, "").trim()
            val candidate = structuralCandidate
                .replace(markdownInlineCodePattern, "$1")
                .replace(markdownStrikePattern, "$1")
                .replace(markdownTaskPattern, "")
                .replace("**", "")
                .replace("__", "")
                .trim()
                .replaceFirst(listMarkerPattern, "")
                .trim()

            val sectionTitle =
                markdownSectionHeadingPattern.matchEntire(structuralCandidate)?.groupValues?.getOrNull(1)?.trim()
                    ?: boldSectionHeadingPattern.matchEntire(structuralCandidate)?.groupValues?.getOrNull(1)?.trim()
            if (sectionTitle != null) {
                sectionMode = when {
                    changeSectionPattern.matches(sectionTitle) -> ReleaseNoteSectionMode.CHANGES
                    ignoredSectionPattern.matches(sectionTitle) -> ReleaseNoteSectionMode.IGNORE
                    else -> ReleaseNoteSectionMode.IGNORE
                }
                return@mapNotNull null
            }

            if (hasStructuredChangeSections && sectionMode != ReleaseNoteSectionMode.CHANGES) {
                return@mapNotNull null
            }
            if (hasStructuredChangeSections && !listMarkerPattern.containsMatchIn(structuralCandidate)) {
                return@mapNotNull null
            }

            if (candidate.isBlank() ||
                candidate.matches(markdownHeadingPattern) ||
                candidate.matches(markdownRulePattern)
            ) {
                return@mapNotNull null
            }

            val authorMatch = authorSuffixPattern.find(candidate)
            val authorLogin = authorMatch?.groupValues?.getOrNull(1)
            val exactWording = authorMatch
                ?.let { candidate.removeRange(it.range).trimEnd() }
                ?: candidate

            if (exactWording.isBlank()) return@mapNotNull null

            WhatsNewNote(
                category = categoryFor(exactWording),
                text = exactWording,
                authorLogin = authorLogin,
            )
        }
    }

    internal fun formatReleaseNoteForDisplay(rawText: String): String {
        var text = rawText
            .replaceFirst(conventionalCommitDisplayPattern, "")
            .trim()

        text = text.replaceFirst(leadingAddPattern, "Added")
        text = text
            .replace(iosWordPattern, "iOS")
            .replace(androidWordPattern, "Android")
            .replace(tvWordPattern, "TV")
            .replace(imdbWordPattern, "IMDb")
            .replace(tmdbWordPattern, "TMDB")

        val firstLetterIndex = text.indexOfFirst(Char::isLetter)
        return if (firstLetterIndex < 0) {
            text
        } else {
            text.replaceRange(
                firstLetterIndex,
                firstLetterIndex + 1,
                text[firstLetterIndex].uppercase(),
            )
        }
    }

    private fun categoryFor(text: String): WhatsNewNoteCategory {
        val type = conventionalCommitPattern.matchEntire(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()

        return when (type) {
            "feat" -> WhatsNewNoteCategory.FEATURES
            "fix", "revert" -> WhatsNewNoteCategory.FIXES
            "perf" -> WhatsNewNoteCategory.PERFORMANCE
            "i18n" -> WhatsNewNoteCategory.LOCALIZATION
            else -> WhatsNewNoteCategory.OTHER
        }
    }
}