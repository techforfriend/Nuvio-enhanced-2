package com.nuvio.app.features.party

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.parseHexColor
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_episode_format
import nuvio.composeapp.generated.resources.party_error_ended
import nuvio.composeapp.generated.resources.party_error_full
import nuvio.composeapp.generated.resources.party_error_network
import nuvio.composeapp.generated.resources.party_error_not_found
import nuvio.composeapp.generated.resources.party_error_rate_limited
import nuvio.composeapp.generated.resources.party_error_removed
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_status_buffering
import nuvio.composeapp.generated.resources.party_status_choosing
import nuvio.composeapp.generated.resources.party_status_elsewhere
import nuvio.composeapp.generated.resources.party_status_loading
import nuvio.composeapp.generated.resources.party_status_offline
import nuvio.composeapp.generated.resources.party_status_ready
import nuvio.composeapp.generated.resources.party_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PartyAvatar(
    member: PartyMember,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val tokens = MaterialTheme.nuvio
    val background = member.avatarColor?.let(::parseHexColor) ?: tokens.colors.accent
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = member.displayName.trim().take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!member.avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = member.avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

internal val PartyError.messageRes: StringResource
    get() = when (this) {
        PartyError.NotFound -> Res.string.party_error_not_found
        PartyError.Full -> Res.string.party_error_full
        PartyError.Removed -> Res.string.party_error_removed
        PartyError.Ended -> Res.string.party_error_ended
        PartyError.RateLimited -> Res.string.party_error_rate_limited
        PartyError.SignInRequired -> Res.string.party_sign_in_required
        PartyError.Unavailable -> Res.string.party_unavailable
        PartyError.Network -> Res.string.party_error_network
    }

internal fun ActiveParty.statusRes(member: PartyMember): StringResource {
    val presence = presence[member.id] ?: return Res.string.party_status_offline
    if (presence.mediaKey != media.key) {
        return if (presence.status == PartyMemberStatus.Browsing) {
            Res.string.party_status_choosing
        } else {
            Res.string.party_status_elsewhere
        }
    }
    return when (presence.status) {
        PartyMemberStatus.Ready -> Res.string.party_status_ready
        PartyMemberStatus.Buffering -> Res.string.party_status_buffering
        PartyMemberStatus.Loading -> Res.string.party_status_loading
        PartyMemberStatus.Browsing -> Res.string.party_status_choosing
    }
}

@Composable
internal fun PartyMedia.displayTitle(): String {
    val season = season
    val episode = episode
    if (season == null || episode == null) return title
    val label = stringResource(Res.string.party_episode_format, season, episode)
    return if (title.isBlank()) label else "$title · $label"
}
