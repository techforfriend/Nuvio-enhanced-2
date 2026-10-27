package com.nuvio.app.features.player

/**
 * Reads the chapters once per source, when it has loaded: libmpv lists them itself, ExoPlayer
 * reads them from the file (when allowed), so a rebuffer must not read them again.
 */
internal suspend fun PlayerScreenRuntime.loadChaptersIfNeeded() {
    val controller = playerController ?: return
    val sourceUrl = activeSourceUrl
    if (playerControllerSourceUrl != sourceUrl || chaptersLoadedForSourceUrl == sourceUrl) return
    val loaded = PlayerChapters.normalize(controller.getChapters())
    if (activeSourceUrl != sourceUrl || playerController !== controller) return
    chaptersLoadedForSourceUrl = sourceUrl
    chapters = loaded
}

internal fun PlayerScreenRuntime.openChaptersPanel() {
    if (chapters.isEmpty()) return
    showChaptersPanel = true
    showSourcesPanel = false
    showEpisodesPanel = false
    // Fork panels
    showQualityPanel = false
    showSpeedPanel = false
    showLiveChannelsPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.selectChapter(index: Int) {
    val chapter = chapters.getOrNull(index) ?: return
    showChaptersPanel = false
    lastManualSkipSeekPositions = playbackSnapshot.positionMs to chapter.startMs
    finishTimelineScrub(chapter.startMs) // fork: clear any in-progress timeline scrub
    playerController?.seekTo(chapter.startMs)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
}

/**
 * Fork: engines that do not implement [PlayerEngineController.getChapters] (iOS mpv) or that read
 * chapters asynchronously (the fork ExoPlayer reader, MKV + MP4) publish them on the playback
 * snapshot instead; use those when the controller returned none.
 */
internal fun PlayerScreenRuntime.adoptSnapshotChaptersIfNeeded() {
    if (chapters.isNotEmpty()) return
    val fromSnapshot = PlayerChapters.normalize(playbackSnapshot.chapters)
    if (fromSnapshot.isNotEmpty()) chapters = fromSnapshot
}
