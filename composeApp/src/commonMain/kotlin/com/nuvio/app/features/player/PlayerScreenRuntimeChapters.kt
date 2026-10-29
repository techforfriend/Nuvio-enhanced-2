package com.nuvio.app.features.player

/**
 * Reads the chapters once per source, when it has loaded: libmpv lists them itself, ExoPlayer
 * reads them from the file (when allowed), so a rebuffer must not read them again.
 */
internal suspend fun PlayerScreenRuntime.loadChaptersIfNeeded() {
    if (!playerSettingsUiState.androidExoChaptersEnabled) return
    val controller = playerController ?: return
    val sourceUrl = activeSourceUrl
    if (!isControllerOnActivePlayback() || chaptersLoadedForSourceUrl == sourceUrl) return
    val loaded = PlayerChapters.normalize(controller.getChapters())
    if (activeSourceUrl != sourceUrl || playerController !== controller) return
    chaptersLoadedForSourceUrl = sourceUrl
    chapters = loaded
}

internal fun PlayerScreenRuntime.clearChaptersIfDisabled() {
    if (playerSettingsUiState.androidExoChaptersEnabled) return
    chapters = emptyList()
    chaptersLoadedForSourceUrl = null
    showChaptersPanel = false
}

internal fun PlayerScreenRuntime.openChaptersPanel() {
    if (chapters.isEmpty()) return
    showChaptersPanel = true
    showSourcesPanel = false
    showEpisodesPanel = false
    showQualityPanel = false
    showSpeedPanel = false
    showLiveChannelsPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.selectChapter(index: Int) {
    val chapter = chapters.getOrNull(index) ?: return
    showChaptersPanel = false
    lastManualSkipSeekPositions = playbackSnapshot.positionMs to chapter.startMs
    finishTimelineScrub(chapter.startMs)
    playerController?.seekTo(chapter.startMs)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
}

internal fun PlayerScreenRuntime.adoptSnapshotChaptersIfNeeded() {
    if (!playerSettingsUiState.androidExoChaptersEnabled) return
    if (chapters.isNotEmpty()) return
    val fromSnapshot = PlayerChapters.normalize(playbackSnapshot.chapters)
    if (fromSnapshot.isNotEmpty()) chapters = fromSnapshot
}
