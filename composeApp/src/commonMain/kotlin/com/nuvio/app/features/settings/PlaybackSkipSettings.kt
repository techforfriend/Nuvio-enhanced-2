package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerSettingsRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.app.features.player.SKIP_INTRO_OFFSET_MAX_SECONDS
import com.nuvio.app.features.player.SKIP_INTRO_OFFSET_SLIDER_SECONDS
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackSkipSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    var showAutoSkipSegmentDialog by remember { mutableStateOf(false) }
    var showSkipIntroOffsetDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_skip_segments),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_skip_intro_outro_recap),
                    description = stringResource(Res.string.settings_playback_skip_intro_outro_recap_description),
                    checked = settings.skipIntroEnabled,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setSkipIntroEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_skip_intro_offset),
                    description = skipIntroOffsetLabel(settings.skipIntroOffsetSeconds),
                    enabled = settings.skipIntroEnabled && !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { showSkipIntroOffsetDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_auto_skip_segments),
                    description = autoSkipSelectionSummary(settings.autoSkipSegmentTypes),
                    enabled = settings.skipIntroEnabled && !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { showAutoSkipSegmentDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_anime_skip),
                    description = stringResource(Res.string.settings_playback_anime_skip_description),
                    checked = settings.animeSkipEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setAnimeSkipEnabled,
                )
                if (settings.animeSkipEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    var showAnimeSkipClientIdDialog by remember { mutableStateOf(false) }
                    val notSetLabel = stringResource(Res.string.settings_playback_not_set)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_anime_skip_client_id),
                        description = settings.animeSkipClientId.ifBlank { notSetLabel },
                        isTablet = isTablet,
                        onClick = { showAnimeSkipClientIdDialog = true },
                    )
                    if (showAnimeSkipClientIdDialog) {
                        AnimeSkipClientIdDialog(
                            initialValue = settings.animeSkipClientId,
                            onSave = {
                                PlayerSettingsRepository.setAnimeSkipClientId(it)
                                showAnimeSkipClientIdDialog = false
                            },
                            onDismiss = { showAnimeSkipClientIdDialog = false },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_intro_submit_enabled),
                    description = stringResource(Res.string.settings_playback_intro_submit_enabled_description),
                    checked = settings.introSubmitEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setIntroSubmitEnabled,
                )
                if (settings.introSubmitEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    var showIntroDbApiKeyDialog by remember { mutableStateOf(false) }
                    val notSetLabel = stringResource(Res.string.settings_playback_not_set)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_introdb_api_key),
                        description = settings.introDbApiKey.ifBlank { notSetLabel },
                        isTablet = isTablet,
                        onClick = { showIntroDbApiKeyDialog = true },
                    )
                    if (showIntroDbApiKeyDialog) {
                        IntroDbApiKeyDialog(
                            initialValue = settings.introDbApiKey,
                            onSave = {
                                PlayerSettingsRepository.setIntroDbApiKey(it)
                                showIntroDbApiKeyDialog = false
                            },
                            onDismiss = { showIntroDbApiKeyDialog = false },
                        )
                    }
                }
            }
        }
    }

    if (showSkipIntroOffsetDialog) {
        SkipIntroOffsetDialog(
            selectedSeconds = settings.skipIntroOffsetSeconds,
            onSelected = { seconds ->
                PlayerSettingsRepository.setSkipIntroOffsetSeconds(seconds)
                showSkipIntroOffsetDialog = false
            },
            onDismiss = { showSkipIntroOffsetDialog = false },
        )
    }

    if (showAutoSkipSegmentDialog) {
        AutoSkipSegmentSelectionDialog(
            selectedTypes = settings.autoSkipSegmentTypes,
            onTypeToggled = PlayerSettingsRepository::setAutoSkipSegmentTypeEnabled,
            onDismiss = { showAutoSkipSegmentDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnimeSkipClientIdDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_anime_skip_client_id),
    ) {
        Text(
            text = stringResource(Res.string.settings_playback_anime_skip_client_id_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = { onSave(value.trim()) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun IntroDbApiKeyDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initialValue) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val invalidKeyMessage = stringResource(Res.string.settings_playback_introdb_invalid_key)

    DialogSurface(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        title = stringResource(Res.string.settings_playback_introdb_api_key),
    ) {
        Text(
            text = stringResource(Res.string.settings_playback_introdb_api_key_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsSecretTextField(
            value = value,
            onValueChange = {
                value = it
                errorMessage = null
            },
            label = stringResource(Res.string.settings_playback_introdb_api_key),
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null,
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                enabled = !isVerifying,
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) {
                        onSave(trimmed)
                        return@DialogButton
                    }

                    if (trimmed == initialValue) {
                        onDismiss()
                        return@DialogButton
                    }

                    isVerifying = true
                    errorMessage = null
                    scope.launch {
                        val isValid = com.nuvio.app.features.player.skip.SkipIntroRepository.verifyIntroDbApiKey(trimmed)
                        isVerifying = false
                        if (isValid) {
                            onSave(trimmed)
                        } else {
                            errorMessage = invalidKeyMessage
                        }
                    }
                },
                style = DialogButtonStyle.Primary,
                loading = isVerifying,
            )
        }
    }
}

@Composable
private fun skipIntroOffsetLabel(seconds: Int): String = when {
    seconds < 0 -> stringResource(Res.string.settings_playback_skip_intro_offset_earlier, -seconds)
    seconds > 0 -> stringResource(Res.string.settings_playback_skip_intro_offset_later, seconds)
    else -> stringResource(Res.string.settings_playback_skip_intro_offset_on_time)
}

@Composable
private fun SkipIntroOffsetDialog(
    selectedSeconds: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var seconds by remember { mutableStateOf(selectedSeconds) }
    var input by remember { mutableStateOf(selectedSeconds.toString()) }
    val sliderRange = SKIP_INTRO_OFFSET_SLIDER_SECONDS.toFloat()

    fun update(value: Int, fromInput: Boolean = false) {
        seconds = value.coerceIn(-SKIP_INTRO_OFFSET_MAX_SECONDS, SKIP_INTRO_OFFSET_MAX_SECONDS)
        if (!fromInput) input = seconds.toString()
    }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_skip_intro_offset),
        message = stringResource(Res.string.settings_playback_skip_intro_offset_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = skipIntroOffsetLabel(seconds),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Slider(
                value = seconds.toFloat().coerceIn(-sliderRange, sliderRange),
                onValueChange = { update(it.roundToInt()) },
                valueRange = -sliderRange..sliderRange,
                steps = SKIP_INTRO_OFFSET_SLIDER_SECONDS * 2 - 1,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_skip_intro_offset_earlier, SKIP_INTRO_OFFSET_SLIDER_SECONDS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_skip_intro_offset_later, SKIP_INTRO_OFFSET_SLIDER_SECONDS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_skip_intro_offset_input),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { text ->
                            val filtered = text.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }.take(4)
                            input = filtered
                            filtered.toIntOrNull()?.let { update(it, fromInput = true) }
                        },
                        modifier = Modifier
                            .width(80.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                Text(
                    text = stringResource(Res.string.settings_playback_skip_intro_offset_unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_reset),
                onClick = { update(0) },
            )
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = { onSelected(seconds) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
