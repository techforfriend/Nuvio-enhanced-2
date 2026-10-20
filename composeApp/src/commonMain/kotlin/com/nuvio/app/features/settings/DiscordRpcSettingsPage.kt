package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.discord.DiscordGatewayState
import com.nuvio.app.features.discord.DiscordLoginWebView
import com.nuvio.app.features.discord.DiscordRpcActivityType
import com.nuvio.app.features.discord.DiscordRpcRepository
import com.nuvio.app.features.discord.DiscordRpcStatus
import com.nuvio.app.features.discord.DiscordRpcUiState
import com.nuvio.app.features.player.PlayerSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.discord_rpc_activity_type_description
import nuvio.composeapp.generated.resources.discord_rpc_activity_type_title
import nuvio.composeapp.generated.resources.discord_rpc_application_id_description
import nuvio.composeapp.generated.resources.discord_rpc_application_id_label
import nuvio.composeapp.generated.resources.discord_rpc_application_id_title
import nuvio.composeapp.generated.resources.discord_rpc_close
import nuvio.composeapp.generated.resources.discord_rpc_enable_description
import nuvio.composeapp.generated.resources.discord_rpc_enable_title
import nuvio.composeapp.generated.resources.discord_rpc_hide_on_pause_description
import nuvio.composeapp.generated.resources.discord_rpc_hide_on_pause_title
import nuvio.composeapp.generated.resources.discord_rpc_last_error
import nuvio.composeapp.generated.resources.discord_rpc_logged_in_as
import nuvio.composeapp.generated.resources.discord_rpc_logging_in
import nuvio.composeapp.generated.resources.discord_rpc_login_button
import nuvio.composeapp.generated.resources.discord_rpc_login_description
import nuvio.composeapp.generated.resources.discord_rpc_login_failed
import nuvio.composeapp.generated.resources.discord_rpc_login_title
import nuvio.composeapp.generated.resources.discord_rpc_logout
import nuvio.composeapp.generated.resources.discord_rpc_private_viewing_notice
import nuvio.composeapp.generated.resources.discord_rpc_save
import nuvio.composeapp.generated.resources.discord_rpc_section_account
import nuvio.composeapp.generated.resources.discord_rpc_section_advanced
import nuvio.composeapp.generated.resources.discord_rpc_section_display
import nuvio.composeapp.generated.resources.discord_rpc_section_presence
import nuvio.composeapp.generated.resources.discord_rpc_show_imdb_button_description
import nuvio.composeapp.generated.resources.discord_rpc_show_imdb_button_title
import nuvio.composeapp.generated.resources.discord_rpc_show_playback_state_description
import nuvio.composeapp.generated.resources.discord_rpc_show_playback_state_title
import nuvio.composeapp.generated.resources.discord_rpc_show_poster_description
import nuvio.composeapp.generated.resources.discord_rpc_show_poster_title
import nuvio.composeapp.generated.resources.discord_rpc_show_timestamps_description
import nuvio.composeapp.generated.resources.discord_rpc_show_timestamps_title
import nuvio.composeapp.generated.resources.discord_rpc_show_title_as_name_description
import nuvio.composeapp.generated.resources.discord_rpc_show_title_as_name_title
import nuvio.composeapp.generated.resources.discord_rpc_status_connecting
import nuvio.composeapp.generated.resources.discord_rpc_status_description
import nuvio.composeapp.generated.resources.discord_rpc_status_failed
import nuvio.composeapp.generated.resources.discord_rpc_status_presence_active
import nuvio.composeapp.generated.resources.discord_rpc_status_title
import nuvio.composeapp.generated.resources.discord_rpc_status_waiting
import nuvio.composeapp.generated.resources.discord_rpc_test_active
import nuvio.composeapp.generated.resources.discord_rpc_test_button
import nuvio.composeapp.generated.resources.discord_rpc_tos_notice
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.discordRpcSettingsContent(
    isTablet: Boolean,
) {
    item {
        DiscordRpcAccountSection(isTablet = isTablet)
    }
    item {
        DiscordRpcPresenceSection(isTablet = isTablet)
    }
    item {
        DiscordRpcDisplaySection(isTablet = isTablet)
    }
    item {
        DiscordRpcAdvancedSection(isTablet = isTablet)
    }
}

@Composable
private fun rememberDiscordRpcUiState(): DiscordRpcUiState {
    remember { DiscordRpcRepository.ensureLoaded() }
    val uiState by DiscordRpcRepository.uiState.collectAsStateWithLifecycle()
    return uiState
}

@Composable
private fun DiscordRpcAccountSection(
    isTablet: Boolean,
) {
    val uiState = rememberDiscordRpcUiState()
    var showLogin by rememberSaveable { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.discord_rpc_section_account),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            DiscordAccountRow(
                isTablet = isTablet,
                uiState = uiState,
                onLoginClick = { showLogin = true },
                onLogoutClick = DiscordRpcRepository::logout,
            )
        }
    }

    if (showLogin) {
        DiscordLoginDialog(
            onDismiss = { showLogin = false },
            onTokenReceived = { token ->
                showLogin = false
                DiscordRpcRepository.login(token)
            },
        )
    }
}

@Composable
private fun DiscordRpcPresenceSection(
    isTablet: Boolean,
) {
    val uiState = rememberDiscordRpcUiState()
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings

    SettingsSection(
        title = stringResource(Res.string.discord_rpc_section_presence),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_enable_title),
                description = if (playerSettings.privateViewingEnabled) {
                    stringResource(Res.string.discord_rpc_private_viewing_notice)
                } else {
                    stringResource(Res.string.discord_rpc_enable_description)
                },
                checked = settings.enabled,
                enabled = uiState.isLoggedIn,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setEnabled,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChipRow(
                title = stringResource(Res.string.discord_rpc_activity_type_title),
                description = stringResource(Res.string.discord_rpc_activity_type_description),
                isTablet = isTablet,
            ) {
                val selectedType = DiscordRpcActivityType.fromValue(settings.activityType)
                DiscordRpcActivityType.entries.forEach { type ->
                    Chip(
                        label = stringResource(type.labelRes),
                        selected = selectedType == type,
                        onClick = { DiscordRpcRepository.setActivityType(type) },
                    )
                }
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChipRow(
                title = stringResource(Res.string.discord_rpc_status_title),
                description = stringResource(Res.string.discord_rpc_status_description),
                isTablet = isTablet,
            ) {
                val selectedStatus = DiscordRpcStatus.fromValue(settings.status)
                DiscordRpcStatus.entries.forEach { status ->
                    Chip(
                        label = stringResource(status.labelRes),
                        selected = selectedStatus == status,
                        onClick = { DiscordRpcRepository.setStatus(status) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscordRpcDisplaySection(
    isTablet: Boolean,
) {
    val settings = rememberDiscordRpcUiState().settings

    SettingsSection(
        title = stringResource(Res.string.discord_rpc_section_display),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_show_title_as_name_title),
                description = stringResource(Res.string.discord_rpc_show_title_as_name_description),
                checked = settings.showTitleAsName,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setShowTitleAsName,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_show_poster_title),
                description = stringResource(Res.string.discord_rpc_show_poster_description),
                checked = settings.showPoster,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setShowPoster,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_show_playback_state_title),
                description = stringResource(Res.string.discord_rpc_show_playback_state_description),
                checked = settings.showPlaybackState,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setShowPlaybackState,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_show_timestamps_title),
                description = stringResource(Res.string.discord_rpc_show_timestamps_description),
                checked = settings.showTimestamps,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setShowTimestamps,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_hide_on_pause_title),
                description = stringResource(Res.string.discord_rpc_hide_on_pause_description),
                checked = settings.hideOnPause,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setHideOnPause,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_rpc_show_imdb_button_title),
                description = stringResource(Res.string.discord_rpc_show_imdb_button_description),
                checked = settings.showImdbButton,
                isTablet = isTablet,
                onCheckedChange = DiscordRpcRepository::setShowImdbButton,
            )
        }
    }
}

@Composable
private fun DiscordRpcAdvancedSection(
    isTablet: Boolean,
) {
    val settings = rememberDiscordRpcUiState().settings

    SettingsSection(
        title = stringResource(Res.string.discord_rpc_section_advanced),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            DiscordApplicationIdRow(
                isTablet = isTablet,
                value = settings.applicationId,
                onCommitted = DiscordRpcRepository::setApplicationId,
            )
        }
    }
}

@Composable
private fun DiscordAccountRow(
    isTablet: Boolean,
    uiState: DiscordRpcUiState,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val user = uiState.user

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.isLoggedIn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val avatarUrl = user?.avatarUrl
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.discord_rpc_logged_in_as,
                            user?.displayName.orEmpty(),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    user?.let { account -> account.username?.takeIf { it.isNotBlank() && it != account.displayName } }?.let { username ->
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = discordConnectionLabel(uiState),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.connectionState == DiscordGatewayState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onLogoutClick) {
                    Text(stringResource(Res.string.discord_rpc_logout))
                }
            }
            uiState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = stringResource(Res.string.discord_rpc_last_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = DiscordRpcRepository::sendTestPresence,
                enabled = !uiState.isTestActive,
            ) {
                Text(stringResource(Res.string.discord_rpc_test_button))
            }
            if (uiState.isTestActive) {
                Text(
                    text = stringResource(Res.string.discord_rpc_test_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = stringResource(Res.string.discord_rpc_login_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onLoginClick,
                    enabled = !uiState.isLoggingIn,
                ) {
                    Text(stringResource(Res.string.discord_rpc_login_button))
                }
                if (uiState.isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(Res.string.discord_rpc_logging_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (uiState.tokenExpired) {
                Text(
                    text = stringResource(Res.string.discord_rpc_login_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = stringResource(Res.string.discord_rpc_tos_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun discordConnectionLabel(uiState: DiscordRpcUiState): String =
    when (uiState.connectionState) {
        DiscordGatewayState.Ready -> stringResource(Res.string.discord_rpc_status_presence_active)
        DiscordGatewayState.Connecting,
        DiscordGatewayState.Reconnecting,
        -> stringResource(Res.string.discord_rpc_status_connecting)
        DiscordGatewayState.Failed,
        DiscordGatewayState.AuthenticationFailed,
        -> stringResource(Res.string.discord_rpc_status_failed)
        DiscordGatewayState.Idle,
        DiscordGatewayState.Closed,
        -> stringResource(Res.string.discord_rpc_status_waiting)
    }

@Composable
private fun DiscordApplicationIdRow(
    isTablet: Boolean,
    value: String,
    onCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim().filter(Char::isDigit)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.discord_rpc_application_id_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.discord_rpc_application_id_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.discord_rpc_application_id_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onCommitted(normalizedDraft)
                },
                enabled = normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.discord_rpc_save))
            }
        }
    }
}

@Composable
private fun DiscordLoginDialog(
    onDismiss: () -> Unit,
    onTokenReceived: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.discord_rpc_close),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.discord_rpc_login_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    DiscordLoginWebView(
                        modifier = Modifier.fillMaxSize(),
                        onLoginCompleted = onTokenReceived,
                    )
                }
            }
        }
    }
}
