package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerToolbarButton
import com.nuvio.app.features.player.PlayerToolbarLayout
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val PlayerToolbarButton.labelRes: StringResource
    get() = when (this) {
        PlayerToolbarButton.NEXT_EPISODE -> Res.string.player_next_episode
        PlayerToolbarButton.SPEED -> Res.string.compose_player_speed
        PlayerToolbarButton.RESIZE -> Res.string.player_toolbar_button_resize
        PlayerToolbarButton.SUBTITLES -> Res.string.compose_player_subtitles
        PlayerToolbarButton.CHAPTERS -> Res.string.player_chapters
        PlayerToolbarButton.AUDIO -> Res.string.compose_player_audio
        PlayerToolbarButton.QUALITY -> Res.string.player_toolbar_button_quality
        PlayerToolbarButton.SOURCES -> Res.string.compose_player_sources
        PlayerToolbarButton.EPISODES -> Res.string.compose_player_episodes
        PlayerToolbarButton.RATE -> Res.string.user_rating_action_rate
        PlayerToolbarButton.EXTERNAL_PLAYER -> Res.string.streams_open_external_player
        PlayerToolbarButton.PLAYBACK_INFO -> Res.string.compose_player_playback_info
        PlayerToolbarButton.SWITCH_ENGINE -> Res.string.cd_switch_player_engine
        PlayerToolbarButton.VIDEO_SETTINGS -> Res.string.player_action_video_settings
        PlayerToolbarButton.SUBMIT_INTRO -> Res.string.submit_intro_action
    }

@Composable
internal fun PlayerToolbarSettingsDialog(
    layout: PlayerToolbarLayout,
    onLayoutChange: (PlayerToolbarLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_toolbar_layout),
        message = stringResource(Res.string.settings_playback_toolbar_note),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolbarGroup(
                title = stringResource(Res.string.settings_playback_toolbar_shown),
                visible = true,
                layout = layout,
                onLayoutChange = onLayoutChange,
            )
            ToolbarGroup(
                title = stringResource(Res.string.settings_playback_toolbar_hidden),
                visible = false,
                layout = layout,
                onLayoutChange = onLayoutChange,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_reset),
                onClick = { onLayoutChange(PlayerToolbarLayout.Default) },
            )
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun ToolbarGroup(
    title: String,
    visible: Boolean,
    layout: PlayerToolbarLayout,
    onLayoutChange: (PlayerToolbarLayout) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = tokens.colors.textSecondary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    val groupIndices = layout.entries.indices.filter { layout.entries[it].visible == visible }
    if (groupIndices.isEmpty()) {
        Text(
            text = stringResource(Res.string.settings_playback_toolbar_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        return
    }
    groupIndices.forEachIndexed { position, entryIndex ->
        val entry = layout.entries[entryIndex]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(entry.button.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolbarRowAction(
                icon = Icons.Rounded.KeyboardArrowUp,
                description = stringResource(Res.string.settings_playback_toolbar_move_up),
                enabled = position > 0,
                onClick = { onLayoutChange(layout.moved(entryIndex, groupIndices[position - 1])) },
            )
            ToolbarRowAction(
                icon = Icons.Rounded.KeyboardArrowDown,
                description = stringResource(Res.string.settings_playback_toolbar_move_down),
                enabled = position < groupIndices.lastIndex,
                onClick = { onLayoutChange(layout.moved(entryIndex, groupIndices[position + 1])) },
            )
            ToolbarRowAction(
                icon = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                description = stringResource(
                    if (visible) Res.string.settings_playback_toolbar_hide else Res.string.settings_playback_toolbar_show,
                ),
                enabled = true,
                onClick = {
                    val toggled = layout.withVisibility(entry.button, !visible)
                    onLayoutChange(toggled.moved(entryIndex, toggled.entries.lastIndex))
                },
            )
        }
    }
}

@Composable
private fun ToolbarRowAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}
