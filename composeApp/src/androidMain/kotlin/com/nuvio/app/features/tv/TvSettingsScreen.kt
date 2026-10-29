package com.nuvio.app.features.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.tmdb.TmdbSettingsRepository

/**
 * Phase 4 TV Settings: only TV-relevant rows. Phone-only options
 * (Downloads, mobile data, PiP) are deliberately not shown here.
 * Rows cycle/toggle on click; TMDB language is shown read-only because
 * text input on a remote is painful — it stays editable on the phone.
 */
@Composable
fun TvSettingsScreen(
    onBack: () -> Unit,
) {
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val tmdbSettings by TmdbSettingsRepository.uiState.collectAsStateWithLifecycle()
    val p2pSettings by P2pSettingsRepository.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        PlayerSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        P2pSettingsRepository.ensureLoaded()
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
                )
            }
            item {
                TvSettingsRow(
                    label = "Playback engine",
                    value = playerSettings.androidPlaybackEngine.label,
                    onClick = {
                        val next = when (playerSettings.androidPlaybackEngine) {
                            AndroidPlaybackEngine.Auto -> AndroidPlaybackEngine.ExoPlayer
                            AndroidPlaybackEngine.ExoPlayer -> AndroidPlaybackEngine.Libmpv
                            AndroidPlaybackEngine.Libmpv -> AndroidPlaybackEngine.Auto
                        }
                        PlayerSettingsRepository.setAndroidPlaybackEngine(next)
                    },
                )
            }
            item {
                TvSettingsRow(
                    label = "Subtitle language",
                    value = playerSettings.preferredSubtitleLanguage,
                    onClick = {
                        val options = listOf(
                            SubtitleLanguageOption.NONE,
                            SubtitleLanguageOption.DEVICE,
                            "en",
                        )
                        val next = options[(options.indexOf(playerSettings.preferredSubtitleLanguage) + 1) % options.size]
                        PlayerSettingsRepository.setPreferredSubtitleLanguage(next)
                    },
                )
            }
            item {
                TvSettingsRow(
                    label = "Audio language",
                    value = playerSettings.preferredAudioLanguage,
                    onClick = {
                        val options = listOf(
                            AudioLanguageOption.DEVICE,
                            AudioLanguageOption.ORIGINAL,
                            AudioLanguageOption.DEFAULT,
                            "en",
                        )
                        val next = options[(options.indexOf(playerSettings.preferredAudioLanguage) + 1) % options.size]
                        PlayerSettingsRepository.setPreferredAudioLanguage(next)
                    },
                )
            }
            if (P2pSettingsRepository.isVisible) {
                item {
                    TvSettingsRow(
                        label = "P2P (torrent)",
                        value = if (p2pSettings.p2pEnabled) "On" else "Off",
                        onClick = {
                            P2pSettingsRepository.setP2pEnabled(!p2pSettings.p2pEnabled)
                        },
                    )
                }
                item {
                    Text(
                        text = "P2P on karne par torrent download ke saath upload bhi ho sakta hai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                TvSettingsRow(
                    label = "TMDB metadata",
                    value = if (tmdbSettings.enabled) "On" else "Off",
                    onClick = {
                        TmdbSettingsRepository.setEnabled(!tmdbSettings.enabled)
                    },
                )
            }
            item {
                TvSettingsRow(
                    label = "TMDB language",
                    value = tmdbSettings.language,
                    onClick = { },
                )
            }
        }
    }
}

@Composable
private fun TvSettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
