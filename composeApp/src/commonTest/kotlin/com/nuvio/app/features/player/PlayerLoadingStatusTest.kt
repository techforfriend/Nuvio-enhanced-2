package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_loading_buffering
import nuvio.composeapp.generated.resources.player_loading_building
import nuvio.composeapp.generated.resources.player_loading_starting
import nuvio.composeapp.generated.resources.player_loading_subtitles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLoadingStatusTest {
    @Test
    fun `loading status defaults on like TV`() {
        assertTrue(PlayerSettingsUiState().showPlayerLoadingStatus)
    }

    @Test
    fun `disabled status hides every initialization step`() {
        for (controllerReady in listOf(false, true)) {
            for (subtitlesLoading in listOf(false, true)) {
                for (buffering in listOf(false, true)) {
                    assertNull(playerLoadingStatusResource(false, controllerReady, subtitlesLoading, buffering))
                }
            }
        }
    }

    @Test
    fun `status follows real initialization and subtitle work`() {
        assertEquals(Res.string.player_loading_building, playerLoadingStatusResource(true, false, false, true))
        assertEquals(Res.string.player_loading_subtitles, playerLoadingStatusResource(true, true, true, true))
        assertEquals(Res.string.player_loading_buffering, playerLoadingStatusResource(true, true, false, true))
        assertEquals(Res.string.player_loading_starting, playerLoadingStatusResource(true, true, false, false))
    }
}
