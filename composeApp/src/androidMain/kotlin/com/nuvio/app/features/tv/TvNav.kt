package com.nuvio.app.features.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.party.PartyMedia

/**
 * Phase 4 TV navigation: a small backstack (Home → Details → Player, plus
 * Search, Settings, Downloads and Watch Party from Home). BACK pops the
 * stack; the root Home screen has nowhere to pop to, so TV BACK there
 * exits the app as usual. Details hands a resolved [TvPlayRequest] to
 * [TvScreen.Player].
 */
sealed interface TvScreen {
    data object Home : TvScreen
    data class Details(val item: MetaPreview) : TvScreen
    data class Player(val request: TvPlayRequest) : TvScreen
    data object Search : TvScreen
    data object Settings : TvScreen
    data object Downloads : TvScreen
    data class Party(val media: PartyMedia?) : TvScreen
    data object LiveTv : TvScreen
}

@Composable
fun TvNavHost() {
    val stack = remember { mutableStateListOf<TvScreen>(TvScreen.Home) }
    val current = stack.last()
    fun navigate(screen: TvScreen) {
        stack.add(screen)
    }
    fun goBack() {
        if (stack.size > 1) stack.removeLast()
    }
    when (val screen = current) {
        TvScreen.Home -> TvHomeScreen(
            onItemClick = { navigate(TvScreen.Details(it)) },
            onOpenSearch = { navigate(TvScreen.Search) },
            onOpenSettings = { navigate(TvScreen.Settings) },
            onOpenDownloads = { navigate(TvScreen.Downloads) },
            onOpenParty = { navigate(TvScreen.Party(null)) },
            onOpenLiveTv = { navigate(TvScreen.LiveTv) },
        )
        is TvScreen.Details -> TvDetailsScreen(
            item = screen.item,
            onBack = { goBack() },
            onOpenDetails = { navigate(TvScreen.Details(it)) },
            onPlay = { navigate(TvScreen.Player(it)) },
        )
        is TvScreen.Player -> TvPlayerScreen(
            request = screen.request,
            onBack = { goBack() },
            onOpenParty = {
                val request = screen.request
                val media = if (request.metaType != null && request.metaId != null) {
                    PartyMedia(
                        type = request.metaType,
                        metaId = request.metaId,
                        metaType = request.metaType,
                        videoId = request.videoId,
                        season = request.season,
                        episode = request.episode,
                        title = request.title,
                    )
                } else {
                    null
                }
                navigate(TvScreen.Party(media))
            },
        )
        TvScreen.Search -> TvSearchScreen(
            onOpenDetails = { navigate(TvScreen.Details(it)) },
            onBack = { goBack() },
        )
        TvScreen.Settings -> TvSettingsScreen(
            onBack = { goBack() },
        )
        TvScreen.Downloads -> TvDownloadsScreen(
            onBack = { goBack() },
            onPlay = { navigate(TvScreen.Player(it)) },
        )
        is TvScreen.Party -> TvPartyScreen(
            media = screen.media,
            onBack = { goBack() },
            onPlay = { navigate(TvScreen.Player(it)) },
        )
        TvScreen.LiveTv -> TvLiveScreen(
            onBack = { goBack() },
            onPlay = { navigate(TvScreen.Player(it)) },
        )
    }
}
