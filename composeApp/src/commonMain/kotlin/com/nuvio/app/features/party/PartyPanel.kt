package com.nuvio.app.features.party

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerDialogButton
import com.nuvio.app.features.player.PlayerPanelHeader
import com.nuvio.app.features.player.PlayerSidePanel
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_remove
import nuvio.composeapp.generated.resources.party_code_label
import nuvio.composeapp.generated.resources.party_copy_invite
import nuvio.composeapp.generated.resources.party_end
import nuvio.composeapp.generated.resources.party_end_message
import nuvio.composeapp.generated.resources.party_end_title
import nuvio.composeapp.generated.resources.party_host
import nuvio.composeapp.generated.resources.party_host_only_description
import nuvio.composeapp.generated.resources.party_host_only_note
import nuvio.composeapp.generated.resources.party_host_only_title
import nuvio.composeapp.generated.resources.party_invite_copied
import nuvio.composeapp.generated.resources.party_invite_message
import nuvio.composeapp.generated.resources.party_leave
import nuvio.composeapp.generated.resources.party_now_watching
import nuvio.composeapp.generated.resources.party_play_for_everyone
import nuvio.composeapp.generated.resources.party_reconnecting
import nuvio.composeapp.generated.resources.party_remove_message
import nuvio.composeapp.generated.resources.party_remove_title
import nuvio.composeapp.generated.resources.party_seats
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_start_action
import nuvio.composeapp.generated.resources.party_start_description
import nuvio.composeapp.generated.resources.party_start_title
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_unavailable
import nuvio.composeapp.generated.resources.party_watch_with_party
import nuvio.composeapp.generated.resources.party_you
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PartyPanel(
    visible: Boolean,
    state: PartyUiState,
    playerMediaKey: String,
    isSupported: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onPlayForEveryone: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        width = 440.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            PlayerPanelHeader(title = stringResource(Res.string.party_title)) {
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }
            Spacer(Modifier.height(20.dp))
            val party = state.party
            if (party == null) {
                PartyStartContent(
                    busy = state.busy,
                    isSupported = isSupported,
                    canStart = canStart,
                    onStart = onStart,
                )
            } else {
                PartyContent(
                    party = party,
                    playerMediaKey = playerMediaKey,
                    onPlayForEveryone = onPlayForEveryone,
                    onWatchWithParty = onWatchWithParty,
                    onLeave = onLeave,
                    onEnd = onEnd,
                    onRemove = onRemove,
                    onHostOnlyChange = onHostOnlyChange,
                )
            }
        }
    }
}

@Composable
private fun PartyStartContent(
    busy: Boolean,
    isSupported: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Groups,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = stringResource(Res.string.party_start_title),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = stringResource(Res.string.party_start_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        when {
            !isSupported -> PartyNote(stringResource(Res.string.party_unavailable))
            !canStart -> PartyNote(stringResource(Res.string.party_sign_in_required))
            else -> DialogButton(
                text = stringResource(Res.string.party_start_action),
                onClick = onStart,
                style = DialogButtonStyle.Primary,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PartyContent(
    party: ActiveParty,
    playerMediaKey: String,
    onPlayForEveryone: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var memberToRemove by remember { mutableStateOf<PartyMember?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PartyCodeCard(code = party.code, connected = party.connected)

        Text(
            text = stringResource(Res.string.party_seats, party.members.size, party.maxMembers),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textMuted,
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            party.members.forEach { member ->
                PartyMemberRow(
                    party = party,
                    member = member,
                    onRemove = if (party.isHost && member.id != party.memberId) {
                        { memberToRemove = member }
                    } else {
                        null
                    },
                )
            }
        }

        if (party.isHost) {
            val shape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.colors.surfaceCard)
                    .border(1.dp, tokens.colors.borderDefault, shape),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.party_host_only_title),
                    description = stringResource(Res.string.party_host_only_description),
                    checked = party.hostOnly,
                    isTablet = false,
                    onCheckedChange = onHostOnlyChange,
                )
            }
        } else if (party.hostOnly) {
            PartyNote(stringResource(Res.string.party_host_only_note))
        }

        if (playerMediaKey != party.media.key) {
            PartyMediaCard(
                media = party.media,
                onWatchWithParty = onWatchWithParty,
                onPlayForEveryone = onPlayForEveryone.takeIf { party.canControl },
            )
        }

        Spacer(Modifier.height(4.dp))

        DialogButton(
            text = stringResource(Res.string.party_leave),
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
        )
        if (party.isHost && party.members.size > 1) {
            DialogButton(
                text = stringResource(Res.string.party_end),
                onClick = { confirmEnd = true },
                style = DialogButtonStyle.Destructive,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    memberToRemove?.let { member ->
        PartyConfirmDialog(
            title = stringResource(Res.string.party_remove_title, member.displayName),
            message = stringResource(Res.string.party_remove_message),
            confirmLabel = stringResource(Res.string.action_remove),
            onConfirm = {
                memberToRemove = null
                onRemove(member)
            },
            onDismiss = { memberToRemove = null },
        )
    }
    if (confirmEnd) {
        PartyConfirmDialog(
            title = stringResource(Res.string.party_end_title),
            message = stringResource(Res.string.party_end_message),
            confirmLabel = stringResource(Res.string.party_end),
            onConfirm = {
                confirmEnd = false
                onEnd()
            },
            onDismiss = { confirmEnd = false },
        )
    }
}

@Composable
private fun PartyCodeCard(code: String, connected: Boolean) {
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    val invite = stringResource(Res.string.party_invite_message, formatPartyCode(code), code)
    val copied = stringResource(Res.string.party_invite_copied)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surfaceCard)
            .border(1.dp, tokens.colors.borderDefault, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (connected) stringResource(Res.string.party_code_label) else stringResource(Res.string.party_reconnecting),
            style = MaterialTheme.typography.labelLarge,
            color = if (connected) tokens.colors.textMuted else tokens.colors.accent,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatPartyCode(code),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 4.sp),
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(invite))
                    NuvioToastController.show(copied)
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(Res.string.party_copy_invite),
                    tint = tokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PartyMemberRow(
    party: ActiveParty,
    member: PartyMember,
    onRemove: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    val isSelf = member.id == party.memberId
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PartyAvatar(member = member)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isSelf) "${member.displayName} (${stringResource(Res.string.party_you)})" else member.displayName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.id == party.hostMemberId) {
                    PartyBadge(stringResource(Res.string.party_host))
                }
            }
            Text(
                text = stringResource(party.statusRes(member)),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_remove),
                    tint = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun PartyMediaCard(
    media: PartyMedia,
    onWatchWithParty: () -> Unit,
    onPlayForEveryone: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.accent.copy(alpha = tokens.opacity.hover))
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.party_now_watching, media.displayTitle()),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.party_watch_with_party),
                onClick = onWatchWithParty,
                style = DialogButtonStyle.Primary,
            )
            if (onPlayForEveryone != null) {
                DialogButton(
                    text = stringResource(Res.string.party_play_for_everyone),
                    onClick = onPlayForEveryone,
                )
            }
        }
    }
}

@Composable
private fun PartyBadge(text: String) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun PartyNote(text: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textMuted,
    )
}

@Composable
private fun PartyConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
    ) {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = confirmLabel,
                onClick = onConfirm,
                style = DialogButtonStyle.Destructive,
            )
        }
    }
}
