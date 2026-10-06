package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class WhatsNewSnapshotBuilderTest {
    @Test
    fun cleanReleaseNotesKeepsOnlyChangeBulletsFromStructuredReleaseSections() {
        val raw = """
            # 🚀 Release v0.2.13

            This release is based on the upstream release.

            ---

            ## Variants

            ### Nuvio Full
            This variant is similar to the official version.

            ### Nuvio Enhanced
            This custom variant includes experimental features.

            **Added Features:**
            * Added a feature.
            * Changed an existing behavior by @alice.

            **Bug Fixes:**
            * Fixed a crash by @bob.

            > **💡 Note for iOS Users:**
            Installation guidance should not become a release-note bullet.

            ---

            ## 📥 Downloads

            | Variant | Platform | File |
            | :--- | :--- | :--- |
            | Enhanced | Android | [APK](https://example.com/app.apk) |
        """.trimIndent()

        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(raw)

        assertEquals(
            listOf(
                "Added a feature.",
                "Changed an existing behavior by",
                "Fixed a crash by",
            ),
            cleaned.map(WhatsNewNote::text),
        )
        assertEquals(
            listOf("alice", "bob"),
            cleaned.mapNotNull(WhatsNewNote::authorLogin),
        )
    }

    @Test
    fun sinceYourVersionContainsOnlyNewerReleasesWhenCurrentVersionIsPresent() {
        val releases = listOf(
            release("1.4.0"),
            release("1.3.0"),
            release("1.2.0"),
            release("1.1.0"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.2.0",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertEquals(
            listOf("1.4.0", "1.3.0"),
            snapshot.sinceYourVersion.map(WhatsNewRelease::version),
        )
        assertEquals("1.2.0", snapshot.currentVersion)
    }

    @Test
    fun sinceYourVersionIsNotClaimedWhenCurrentVersionIsOutsideRecentHistory() {
        val releases = listOf(
            release("1.4.0"),
            release("1.3.0"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.2.0",
        )

        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
        assertEquals("1.2.0", snapshot.currentVersion)
    }

    @Test
    fun futureStablePromotionAndBetaReleasesAreAllShownAsNewForCurrentBeta() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("0.5.7", title = "0.5.7"),
                release("0.5.7-beta.2", title = "0.5.7 Beta 2"),
                release("0.5.7-beta.1", title = "0.5.7 Beta 1"),
                release("0.5.6-beta.3", title = "0.5.6 Beta 3"),
            ),
            currentVersion = "0.5.6-beta.3",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertEquals(
            listOf("0.5.7", "0.5.7-beta.2", "0.5.7-beta.1"),
            snapshot.sinceYourVersion.map(WhatsNewRelease::version),
        )
    }

    @Test
    fun currentBetaRemainsUpToDateWhenItIsNewestAvailableRelease() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("0.5.7-beta.2"),
                release("0.5.7-beta.1"),
                release("0.5.6"),
            ),
            currentVersion = "0.5.7-beta.2",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun currentVersionAtLatestReleaseReportsNoNewReleases() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("1.4.0"),
                release("1.3.0"),
            ),
            currentVersion = "1.4.0",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun anInvalidCurrentVersionDoesNotProduceAFalseSinceCount() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("1.4.0"),
                release("1.3.0"),
                release("1.2.0"),
            ),
            currentVersion = "not-a-version",
        )

        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun emptyReleaseListProducesAnIncompleteSnapshotWithoutFalseUpdates() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = emptyList(),
            currentVersion = "1.2.0",
        )

        assertTrue(snapshot.releases.isEmpty())
        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun cleanReleaseNotesDecodesCommonHtmlEntitiesWithoutRewritingContributionText() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "- fix(player): handle \"quoted\" title &amp; subtitle @alice",
        )

        assertEquals(1, cleaned.size)
        assertEquals(
            "fix(player): handle \"quoted\" title & subtitle",
            cleaned.single().text,
        )
        assertEquals("alice", cleaned.single().authorLogin)
    }

    @Test
    fun cleanReleaseNotesRemovesCommonMarkdownPresentationSyntax() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            """
            > **💡 Note**
            - fix(player): use `hardware acceleration` instead @alice
            - ~~old wording~~ replaced with the new wording
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "💡 Note",
                "fix(player): use hardware acceleration instead",
                "old wording replaced with the new wording",
            ),
            cleaned.map(WhatsNewNote::text),
        )
        assertEquals("alice", cleaned[1].authorLogin)
    }

    @Test
    fun cleanReleaseNotesGroupsBlockquotesBeforeRemovingListMarkers() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "> - feat(home): add a cleaner hero",
        )

        assertEquals("feat(home): add a cleaner hero", cleaned.single().text)
    }

    @Test
    fun releaseOrderIsNormalizedNewestFirstWithoutDuplicatingVersions() {
        val releases = listOf(
            release("1.2.0"),
            release("1.4.0"),
            release("1.3.0"),
            release("1.4.0", title = "Duplicate"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.2.0",
        )

        assertEquals(
            listOf("1.4.0", "1.3.0", "1.2.0"),
            snapshot.releases.map(WhatsNewRelease::version),
        )
        assertEquals(
            listOf("1.4.0", "1.3.0"),
            snapshot.sinceYourVersion.map(WhatsNewRelease::version),
        )
    }

    @Test
    fun cleanReleaseNotesPreservesAngleBracketTextThatIsNotHtml() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "- fix(player): support <TV> channel names",
        )

        assertEquals(
            "fix(player): support <TV> channel names",
            cleaned.single().text,
        )
    }

    @Test
    fun cleanReleaseNotesDoesNotStripAHyphenThatIsPartOfTheText() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "-not-a-list-item",
        )

        assertEquals(
            "-not-a-list-item",
            cleaned.single().text,
        )
    }

    @Test
    fun cleanReleaseNotesPreservesRepeatedContributionLinesInOrder() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            """
            - fix(player): update subtitle handling @alice
            - fix(player): update subtitle handling @alice
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "fix(player): update subtitle handling",
                "fix(player): update subtitle handling",
            ),
            cleaned.map(WhatsNewNote::text),
        )
        assertEquals(listOf("alice", "alice"), cleaned.map(WhatsNewNote::authorLogin))
    }

    @Test
    fun cleanReleaseNotesPreservesExactContributionWordingAndCredits() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            """
            - feat(player): add smarter subtitle startup @alice
            - fix(home): fix hero alignment [details](https://github.com/example/project) @bob
            - perf(profile): reduce duplicate API work @carol
            - i18n(el): improve Greek wording @dora
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                WhatsNewNoteCategory.FEATURES,
                WhatsNewNoteCategory.FIXES,
                WhatsNewNoteCategory.PERFORMANCE,
                WhatsNewNoteCategory.LOCALIZATION,
            ),
            cleaned.map(WhatsNewNote::category),
        )
        assertEquals(
            "feat(player): add smarter subtitle startup",
            cleaned[0].text,
        )
        assertEquals("alice", cleaned[0].authorLogin)
        assertEquals(
            "fix(home): fix hero alignment details",
            cleaned[1].text,
        )
        assertEquals("bob", cleaned[1].authorLogin)
        assertEquals("perf(profile): reduce duplicate API work", cleaned[2].text)
        assertEquals("carol", cleaned[2].authorLogin)
    }

    @Test
    fun formatReleaseNoteForDisplayNormalizesSentenceStartWithoutRewritingMidSentenceAdd() {
        assertEquals(
            "Added mobile background selection",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("feat(home): add mobile background selection"),
        )
        assertEquals(
            "Added TVDB anime ID preference to avoid per-season IMDB splits (#1952)",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("Add TVDB anime ID preference to avoid per-season IMDB splits (#1952)"),
        )
        assertEquals(
            "Open iOS playback in fullscreen",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("fix(trailer): open ios playback in fullscreen"),
        )
        assertEquals(
            "Match continue watching badge colors to TV",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("fix(home): match continue watching badge colors to tv"),
        )
        assertEquals(
            "Keep Android landscape lock during exit",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("fix(player): keep android landscape lock during exit"),
        )
        assertEquals(
            "Allows users to add profiles",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("Allows users to add profiles"),
        )
    }

    @Test
    fun lastCheckedUsesSingularAndPluralUnitsCorrectly() {
        val cases = listOf(
            "1 minute" to LastCheckedAge(1L, LastCheckedUnit.MINUTE),
            "2 minutes" to LastCheckedAge(2L, LastCheckedUnit.MINUTE),
            "1 hour" to LastCheckedAge(1L, LastCheckedUnit.HOUR),
            "2 hours" to LastCheckedAge(2L, LastCheckedUnit.HOUR),
            "1 day" to LastCheckedAge(1L, LastCheckedUnit.DAY),
            "2 days" to LastCheckedAge(2L, LastCheckedUnit.DAY),
            "1 week" to LastCheckedAge(1L, LastCheckedUnit.WEEK),
            "3 weeks" to LastCheckedAge(3L, LastCheckedUnit.WEEK),
            "1 month" to LastCheckedAge(1L, LastCheckedUnit.MONTH),
            "2 months" to LastCheckedAge(2L, LastCheckedUnit.MONTH),
            "1 year" to LastCheckedAge(1L, LastCheckedUnit.YEAR),
            "2 years" to LastCheckedAge(2L, LastCheckedUnit.YEAR),
        )

        cases.forEach { (label, expected) ->
            val amountMillis = when (expected.unit) {
                LastCheckedUnit.MINUTE -> expected.amount * 60L * 1_000L
                LastCheckedUnit.HOUR -> expected.amount * 60L * 60L * 1_000L
                LastCheckedUnit.DAY -> expected.amount * 24L * 60L * 60L * 1_000L
                LastCheckedUnit.WEEK -> expected.amount * 7L * 24L * 60L * 60L * 1_000L
                LastCheckedUnit.MONTH -> when (expected.amount) {
                    1L -> daysBetween("2026-01-03T12:00:00Z", "2026-02-03T12:00:00Z")
                    else -> daysBetween("2026-01-03T12:00:00Z", "2026-03-03T12:00:00Z")
                } * 24L * 60L * 60L * 1_000L
                LastCheckedUnit.YEAR -> when (expected.amount) {
                    1L -> daysBetween("2025-10-03T12:00:00Z", "2026-10-03T12:00:00Z")
                    else -> daysBetween("2024-10-03T12:00:00Z", "2026-10-03T12:00:00Z")
                } * 24L * 60L * 60L * 1_000L
                LastCheckedUnit.JUST_NOW -> 0L
            }

            val start = Instant.parse(
                when (expected.unit) {
                    LastCheckedUnit.MONTH -> "2026-01-03T12:00:00Z"
                    LastCheckedUnit.YEAR -> "2025-10-03T12:00:00Z"
                    else -> "2026-01-01T12:00:00Z"
                },
            )
            val result = calculateLastCheckedAge(
                fetchedAtMillis = start.toEpochMilliseconds(),
                nowMillis = start.toEpochMilliseconds() + amountMillis,
                timeZone = TimeZone.UTC,
            )

            assertEquals(expected, result, label)
        }
    }

    @Test
    fun lastCheckedUsesCalendarMonthsInsteadOfThirtyDayApproximation() {
        val start = Instant.parse("2026-01-31T12:00:00Z")
        val end = Instant.parse("2026-03-31T12:00:00Z")

        assertEquals(
            LastCheckedAge(2L, LastCheckedUnit.MONTH),
            calculateLastCheckedAge(
                fetchedAtMillis = start.toEpochMilliseconds(),
                nowMillis = end.toEpochMilliseconds(),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun lastCheckedTreatsEndOfMonthAsARealCalendarMonth() {
        val start = Instant.parse("2026-01-31T12:00:00Z")
        val end = Instant.parse("2026-02-28T12:00:00Z")

        assertEquals(
            LastCheckedAge(1L, LastCheckedUnit.MONTH),
            calculateLastCheckedAge(
                fetchedAtMillis = start.toEpochMilliseconds(),
                nowMillis = end.toEpochMilliseconds(),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun lastCheckedUsesLocalCalendarDaysAcrossDaylightSavingTime() {
        val timeZone = TimeZone.of("Europe/Berlin")
        val start = Instant.parse("2026-03-28T02:30:00Z")
        val end = Instant.parse("2026-03-29T01:30:00Z")

        assertEquals(
            LastCheckedAge(1L, LastCheckedUnit.DAY),
            calculateLastCheckedAge(
                fetchedAtMillis = start.toEpochMilliseconds(),
                nowMillis = end.toEpochMilliseconds(),
                timeZone = timeZone,
            ),
        )
    }

    @Test
    fun lastCheckedUsesCalendarYearsAcrossLeapYears() {
        val start = Instant.parse("2024-01-03T12:00:00Z")
        val end = Instant.parse("2025-01-03T12:00:00Z")

        assertEquals(
            LastCheckedAge(1L, LastCheckedUnit.YEAR),
            calculateLastCheckedAge(
                fetchedAtMillis = start.toEpochMilliseconds(),
                nowMillis = end.toEpochMilliseconds(),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun lastCheckedUsesCalendarDaysBeforeFallingBackToHours() {
        val start = Instant.parse("2026-03-01T12:00:00Z")
        val end = Instant.parse("2026-03-08T12:00:00Z")

        assertEquals(
            LastCheckedAge(1L, LastCheckedUnit.WEEK),
            calculateLastCheckedAge(
                fetchedAtMillis = start.toEpochMilliseconds(),
                nowMillis = end.toEpochMilliseconds(),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    private fun daysBetween(start: String, end: String): Long =
        (Instant.parse(end).toEpochMilliseconds() - Instant.parse(start).toEpochMilliseconds()) /
            (24L * 60L * 60L * 1_000L)

    @Test
    fun cleanReleaseNotesPreservesAllItemsFromTheThirteenChangeRelease() {
        val body = """
            - fix(home): match continue watching badge colors to tv @tapframe
            - perf(navigation): reduce root tab switch stalls @tapframe
            - Add TVDB anime ID preference to avoid per-season IMDB splits (#1952) @skoruppa
            - Revert "perf(navigation): reduce tab switch animation stalls" @tapframe
            - fix(player): keep android landscape lock during exit @tapframe
            - fix(profiles): add active profile toast and back button @tapframe
            - fix(profiles): prevent selecting the active profile @tapframe
            - fix(player): hide addons with no streams @tapframe
            - fix(player): start playback without waiting for addon subtitles (#1949) @halibiram
            - fix(trailer): open ios playback in fullscreen @tapframe
            - Fix anime skip mapping, subtitle language detection, watched badges and Polish translations (#1943) @skoruppa
            - fix(library): restore LaunchedEffect import after the 0.4.21 merge @luqmanfadlli
            - feat(streams): add pinned stream sources @luqmanfadlli
        """.trimIndent()

        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(body)

        assertEquals(13, cleaned.size)
        assertEquals(
            "Added pinned stream sources",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay(cleaned.last().text),
        )
        assertEquals(
            "Added TVDB anime ID preference to avoid per-season IMDB splits (#1952)",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay(cleaned[2].text),
        )
    }

    private fun release(
        version: String,
        title: String = version,
        notes: List<WhatsNewNote> = listOf(
            WhatsNewNote(
                category = WhatsNewNoteCategory.OTHER,
                text = "Notes for " + version,
                authorLogin = null,
            ),
        ),
    ): WhatsNewRelease =
        WhatsNewRelease(
            version = version,
            title = title,
            notes = notes,
            publishedAt = null,
            releaseUrl = "https://github.com/luqmanfadlli/NuvioMobile-Enhanced/releases/tag/" + version,
        )
}