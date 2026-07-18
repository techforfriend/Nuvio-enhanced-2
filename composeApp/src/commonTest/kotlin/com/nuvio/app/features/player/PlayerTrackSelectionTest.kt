package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTrackSelectionTest {

    @Test
    fun forcedSelectionUsesPrimaryPreferredLanguageInsteadOfTrackOrder() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "ja", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
            subtitleTrack(index = 2, language = "en", isForced = true),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = listOf("en"),
            requireForced = true,
        )

        assertEquals(2, selectedIndex)
    }

    @Test
    fun forcedSelectionFallsBackToSecondaryPreferredLanguage() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "ja", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
            subtitleTrack(index = 2, language = "fr", isForced = true),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = listOf("en", "fr"),
            requireForced = true,
        )

        assertEquals(2, selectedIndex)
    }

    @Test
    fun forcedSelectionRejectsTracksOutsidePreferredLanguages() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "ja", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = listOf("en"),
            requireForced = true,
        )

        assertEquals(-1, selectedIndex)
    }

    @Test
    fun preferredOnlyFilteringRemovesNonPreferredAddons() {
        val subtitles = listOf(
            addonSubtitle(id = "english", language = "en"),
            addonSubtitle(id = "japanese", language = "ja"),
        )
        val settings = PlayerSettingsUiState(
            preferredSubtitleLanguage = "en",
            subtitleStyle = SubtitleStyleState.DEFAULT.copy(showOnlyPreferredLanguages = true),
        )

        val visibleSubtitles = filterAddonSubtitlesForSettings(
            subtitles = subtitles,
            settings = settings,
        )

        assertEquals(listOf("english"), visibleSubtitles.map { it.id })
    }

    @Test
    fun forcedToggleKeepsPreferredLanguagesForAddonFiltering() {
        val subtitles = listOf(
            addonSubtitle(id = "japanese", language = "ja"),
            addonSubtitle(id = "french", language = "fr"),
            addonSubtitle(id = "english", language = "en"),
        )
        val settings = PlayerSettingsUiState(
            preferredSubtitleLanguage = "en",
            secondaryPreferredSubtitleLanguage = "fr",
            subtitleStyle = SubtitleStyleState.DEFAULT.copy(
                useForcedSubtitles = true,
                showOnlyPreferredLanguages = true,
            ),
        )

        val visibleSubtitles = filterAddonSubtitlesForSettings(
            subtitles = subtitles,
            settings = settings,
        )

        assertEquals(listOf("french", "english"), visibleSubtitles.map { it.id })
    }

    private fun subtitleTrack(
        index: Int,
        language: String,
        isForced: Boolean,
    ) = SubtitleTrack(
        index = index,
        id = "track-$index",
        label = "Track $index",
        language = language,
        isForced = isForced,
    )

    private fun addonSubtitle(
        id: String,
        language: String,
    ) = AddonSubtitle(
        id = id,
        url = "https://example.com/$id.srt",
        language = language,
        display = id,
        addonName = "Addon",
    )
}
