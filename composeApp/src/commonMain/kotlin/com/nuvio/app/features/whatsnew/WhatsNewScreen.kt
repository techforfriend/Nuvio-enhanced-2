package com.nuvio.app.features.whatsnew

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlin.time.Clock
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.UpdateChannel
import com.nuvio.app.features.updater.UpdatePreferences
import com.nuvio.app.features.updater.VersionUtils
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_whats_new
import nuvio.composeapp.generated.resources.updates_channel_beta
import nuvio.composeapp.generated.resources.updates_channel_stable
import nuvio.composeapp.generated.resources.whats_new_category_features
import nuvio.composeapp.generated.resources.whats_new_category_fixes
import nuvio.composeapp.generated.resources.whats_new_category_localization
import nuvio.composeapp.generated.resources.whats_new_category_other
import nuvio.composeapp.generated.resources.whats_new_category_performance
import nuvio.composeapp.generated.resources.whats_new_channel_format
import nuvio.composeapp.generated.resources.whats_new_hide_changes
import nuvio.composeapp.generated.resources.whats_new_last_checked_days
import nuvio.composeapp.generated.resources.whats_new_last_checked_day
import nuvio.composeapp.generated.resources.whats_new_last_checked_hour
import nuvio.composeapp.generated.resources.whats_new_last_checked_minute
import nuvio.composeapp.generated.resources.whats_new_last_checked_hours
import nuvio.composeapp.generated.resources.whats_new_last_checked_just_now
import nuvio.composeapp.generated.resources.whats_new_last_checked_minutes
import nuvio.composeapp.generated.resources.whats_new_last_checked_week
import nuvio.composeapp.generated.resources.whats_new_last_checked_weeks
import nuvio.composeapp.generated.resources.whats_new_last_checked_month
import nuvio.composeapp.generated.resources.whats_new_last_checked_months
import nuvio.composeapp.generated.resources.whats_new_last_checked_year
import nuvio.composeapp.generated.resources.whats_new_last_checked_years
import nuvio.composeapp.generated.resources.whats_new_load_failed
import nuvio.composeapp.generated.resources.whats_new_no_release_notes
import nuvio.composeapp.generated.resources.whats_new_contributor_credit
import nuvio.composeapp.generated.resources.whats_new_open_github
import nuvio.composeapp.generated.resources.whats_new_refresh
import nuvio.composeapp.generated.resources.whats_new_release_format
import nuvio.composeapp.generated.resources.whats_new_show_all_changes
import nuvio.composeapp.generated.resources.whats_new_status_current_ahead
import nuvio.composeapp.generated.resources.whats_new_status_current_ahead_all
import nuvio.composeapp.generated.resources.whats_new_status_newer_releases_available
import nuvio.composeapp.generated.resources.whats_new_status_update_available
import nuvio.composeapp.generated.resources.whats_new_status_unknown
import nuvio.composeapp.generated.resources.whats_new_status_updates_available
import nuvio.composeapp.generated.resources.whats_new_status_up_to_date
import nuvio.composeapp.generated.resources.whats_new_status_up_to_date_all
import nuvio.composeapp.generated.resources.whats_new_unavailable
import nuvio.composeapp.generated.resources.whats_new_refresh_failed_cached
import org.jetbrains.compose.resources.stringResource

private const val PREVIEW_NOTE_CHAR_LIMIT = 420
private const val PREVIEW_MIN_NOTES = 2
private const val PREVIEW_MAX_NOTES = 6
private val NOTE_CATEGORY_ORDER = listOf(
    WhatsNewNoteCategory.FEATURES,
    WhatsNewNoteCategory.FIXES,
    WhatsNewNoteCategory.PERFORMANCE,
    WhatsNewNoteCategory.LOCALIZATION,
    WhatsNewNoteCategory.OTHER,
)

@Composable
fun WhatsNewSettingsScreen(onBack: () -> Unit) {
    val releaseChannelsEnabled = AppUpdaterPlatform.isSupported
    val selectedChannel by UpdatePreferences.shared.channel.collectAsStateWithLifecycle()
    val channel = if (releaseChannelsEnabled) selectedChannel else UpdateChannel.BETA
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var refreshKey by rememberSaveable { mutableIntStateOf(0) }
    var lastHandledRefreshKey by rememberSaveable { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var loadedChannel by remember { mutableStateOf<UpdateChannel?>(null) }

    LaunchedEffect(channel, refreshKey) {
        val forceRefresh = refreshKey != 0 && refreshKey != lastHandledRefreshKey
        lastHandledRefreshKey = refreshKey
        val preserveContent = state is LoadState.Content && loadedChannel == channel
        if (!preserveContent) state = LoadState.Loading
        isRefreshing = true
        try {
            val result = WhatsNewRepository.load(
                channel = channel,
                currentVersion = AppVersionConfig.VERSION_NAME,
                forceRefresh = forceRefresh,
                matchBaseVersion = !releaseChannelsEnabled,
            )
            state = result.fold(
                onSuccess = LoadState::Content,
                onFailure = { LoadState.Error },
            )
            loadedChannel = channel
        } finally {
            isRefreshing = false
        }
    }

    val channelLabel = stringResource(
        when (channel) {
            UpdateChannel.STABLE -> Res.string.updates_channel_stable
            UpdateChannel.BETA -> Res.string.updates_channel_beta
        },
    )
    val buildChannel = if (
        !releaseChannelsEnabled || VersionUtils.isPrerelease(AppVersionConfig.VERSION_NAME)
    ) {
        UpdateChannel.BETA
    } else {
        UpdateChannel.STABLE
    }
    val buildChannelLabel = if (releaseChannelsEnabled) {
        stringResource(
            when (buildChannel) {
                UpdateChannel.STABLE -> Res.string.updates_channel_stable
                UpdateChannel.BETA -> Res.string.updates_channel_beta
            },
        )
    } else {
        null
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_whats_new),
                onBack = onBack,
                actions = {
                    if (isRefreshing) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(NuvioTokens.Icon.md),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.nuvio.colors.textPrimary,
                            )
                        }
                    } else {
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(Res.string.whats_new_refresh),
                                tint = MaterialTheme.nuvio.colors.textPrimary,
                            )
                        }
                    }
                },
            )
        }

        when (val value = state) {
            LoadState.Loading -> item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NuvioLoadingIndicator()
                }
            }

            LoadState.Error -> {
                item {
                    CurrentVersionCard(
                        version = AppVersionConfig.VERSION_NAME,
                        buildChannelLabel = buildChannelLabel,
                        viewingChannelLabel = channelLabel,
                        showViewingChannel = false,
                        lastCheckedLabel = stringResource(Res.string.whats_new_load_failed),
                        status = stringResource(Res.string.whats_new_unavailable),
                    )
                }
                item(key = "unified-whats-new-box-error") {
                    UnifiedWhatsNewBox()
                }
            }

            is LoadState.Content -> {
                val snapshot = value.content.snapshot

                item {
                    CurrentVersionCard(
                        version = snapshot.currentVersion,
                        buildChannelLabel = buildChannelLabel,
                        viewingChannelLabel = channelLabel,
                        showViewingChannel = false,
                        lastCheckedLabel = lastCheckedLabel(value.content.fetchedAtMillis),
                        status = versionStatus(
                            snapshot = snapshot,
                            channelLabel = null,
                            buildChannel = buildChannel,
                        ),
                    )
                }

                if (value.content.isStale && !isRefreshing) {
                    item {
                        Text(
                            text = stringResource(Res.string.whats_new_refresh_failed_cached),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s4),
                        )
                    }
                }

                item(key = "unified-whats-new-box") {
                    UnifiedWhatsNewBox(
                        releases = snapshot.releases,
                    )
                }
            }
        }

        item(key = "whats-new-bottom-spacer") {
            Spacer(modifier = Modifier.size(80.dp))
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Content(val content: WhatsNewContent) : LoadState
}

@Composable
private fun CurrentVersionCard(
    version: String,
    buildChannelLabel: String?,
    viewingChannelLabel: String,
    showViewingChannel: Boolean,
    lastCheckedLabel: String,
    status: String,
) {
    NuvioSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NewReleases,
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTokens.Icon.md),
                    tint = MaterialTheme.nuvio.colors.accent,
                )
                Text(
                    text = stringResource(Res.string.whats_new_release_format, version),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (buildChannelLabel != null) {
                    Text(
                        text = buildChannelLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.nuvio.colors.textSecondary,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
                if (showViewingChannel) {
                    Text(
                        text = stringResource(
                            Res.string.whats_new_channel_format,
                            viewingChannelLabel,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
                Text(
                    text = lastCheckedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun versionStatus(
    snapshot: WhatsNewSnapshot,
    channelLabel: String?,
    buildChannel: UpdateChannel,
): String {
    val latest = snapshot.releases.firstOrNull()
        ?: return stringResource(Res.string.whats_new_status_unknown)

    val latestIsNewer = VersionUtils.isRemoteNewer(latest.version, snapshot.currentVersion)
    val currentIsNewer = VersionUtils.isRemoteNewer(snapshot.currentVersion, latest.version)
    val betaBuildMatchesDisplayedRelease =
        buildChannel == UpdateChannel.BETA &&
            VersionUtils.isPrerelease(latest.version) &&
            VersionUtils.isSameBaseVersion(latest.version, snapshot.currentVersion)

    return when {
        betaBuildMatchesDisplayedRelease -> if (channelLabel != null) {
            stringResource(Res.string.whats_new_status_up_to_date, channelLabel)
        } else {
            stringResource(Res.string.whats_new_status_up_to_date_all)
        }
        latestIsNewer && snapshot.hasCompleteSinceVersion -> {
            val count = snapshot.sinceYourVersion.size.coerceAtLeast(1)
            if (count == 1) {
                stringResource(Res.string.whats_new_status_update_available)
            } else {
                stringResource(Res.string.whats_new_status_updates_available, count)
            }
        }
        latestIsNewer -> stringResource(Res.string.whats_new_status_newer_releases_available)
        currentIsNewer -> if (channelLabel != null) {
            stringResource(Res.string.whats_new_status_current_ahead, channelLabel)
        } else {
            stringResource(Res.string.whats_new_status_current_ahead_all)
        }
        VersionUtils.parse(snapshot.currentVersion) != null &&
            VersionUtils.parse(latest.version) != null -> if (channelLabel != null) {
                stringResource(Res.string.whats_new_status_up_to_date, channelLabel)
            } else {
                stringResource(Res.string.whats_new_status_up_to_date_all)
            }
        else -> stringResource(Res.string.whats_new_status_unknown)
    }
}

@Composable
private fun lastCheckedLabel(fetchedAtMillis: Long): String {
    val age = calculateLastCheckedAge(
        fetchedAtMillis = fetchedAtMillis,
        nowMillis = Clock.System.now().toEpochMilliseconds(),
    )

    return when (age.unit) {
        LastCheckedUnit.JUST_NOW ->
            stringResource(Res.string.whats_new_last_checked_just_now)
        LastCheckedUnit.MINUTE ->
            stringResource(
                if (age.amount == 1L) {
                    Res.string.whats_new_last_checked_minute
                } else {
                    Res.string.whats_new_last_checked_minutes
                },
                age.amount,
            )
        LastCheckedUnit.HOUR ->
            stringResource(
                if (age.amount == 1L) {
                    Res.string.whats_new_last_checked_hour
                } else {
                    Res.string.whats_new_last_checked_hours
                },
                age.amount,
            )
        LastCheckedUnit.DAY ->
            stringResource(
                if (age.amount == 1L) {
                    Res.string.whats_new_last_checked_day
                } else {
                    Res.string.whats_new_last_checked_days
                },
                age.amount,
            )
        LastCheckedUnit.WEEK ->
            stringResource(
                if (age.amount == 1L) {
                    Res.string.whats_new_last_checked_week
                } else {
                    Res.string.whats_new_last_checked_weeks
                },
                age.amount,
            )
        LastCheckedUnit.MONTH ->
            stringResource(
                if (age.amount == 1L) {
                    Res.string.whats_new_last_checked_month
                } else {
                    Res.string.whats_new_last_checked_months
                },
                age.amount,
            )
        LastCheckedUnit.YEAR ->
            stringResource(
                if (age.amount == 1L) {
                    Res.string.whats_new_last_checked_year
                } else {
                    Res.string.whats_new_last_checked_years
                },
                age.amount,
            )
    }
}
@Composable
private fun UnifiedWhatsNewBox(
    modifier: Modifier = Modifier,
    releases: List<WhatsNewRelease> = emptyList(),
) {
    var showGithubChangelog by rememberSaveable { mutableStateOf(false) }
    val latestReleaseWithNotes = releases.firstOrNull { it.notes.isNotEmpty() }

    NuvioSurfaceCard(
        modifier = modifier.animateContentSize(
            animationSpec = tween(
                durationMillis = NuvioTokens.Motion.normalMillis,
                easing = NuvioTokens.Motion.standard,
            ),
        ),
        tonalElevation = 1,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NewReleases,
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTokens.Icon.md),
                    tint = MaterialTheme.nuvio.colors.accent,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "What's New in Nuvio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                    Text(
                        text = "Unified features & improvements overview",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.nuvio.colors.textMuted.copy(alpha = 0.15f)),
            )

            // Section 1: Nuvio Enhanced
            Text(
                text = "✨ Nuvio Enhanced (Features & Fixes)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.nuvio.colors.accent,
            )

            UnifiedFeatureItem(
                title = "Live TV Direct Navigation Toggle",
                description = "Direct ON/OFF toggle in settings to show or hide the Live TV tab in navigation at any time.",
            )
            UnifiedFeatureItem(
                title = "Subtitle Engine Fixes",
                description = "Resolved subtitle loading issues: added automatic GZIP decompression (.gz, .srt.gz, .vtt.gz), sanitized CDN Accept headers, and added automatic ExoPlayer reload fallback.",
            )
            UnifiedFeatureItem(
                title = "Nuvio Read Overview & Ratings",
                description = "Integrated the rich story synopsis card and interactive ratings sheet from AKRusso; removed redundant Director and Writer text sections from details page.",
            )
            UnifiedFeatureItem(
                title = "Reorganized Player Controls",
                description = "Relocated Watch Party, Picture-in-Picture (PiP), HLS Video Quality selector, and Subtitles to the bottom-left area of the player overlay for easier one-handed access.",
            )
            UnifiedFeatureItem(
                title = "Unified What's New Hub",
                description = "Replaced fragmented changelog sections with a single, consolidated overview of all custom and upstream updates.",
            )
            UnifiedFeatureItem(
                title = "Pure CS3 Architecture",
                description = "Purged all legacy Vega provider components and dependencies, delivering a lightweight, stable CloudStream 3 plugin runtime.",
            )
            UnifiedFeatureItem(
                title = "Out-of-the-Box Tracker Sync",
                description = "Trakt.tv, SIMKL, and MDBList are pre-configured with verified credentials for immediate authorization without manual API keys.",
            )
            UnifiedFeatureItem(
                title = "Digital 200% Volume Boost",
                description = "Hardware-accelerated loudness enhancement up to 200% with a dedicated amber HUD indicator for low-volume audio tracks.",
            )
            UnifiedFeatureItem(
                title = "IPTV & Live TV Suite",
                description = "Full Live TV experience with M3U/M3U8 playlists, Xtream Codes API, Stalker Portal, and XMLTV EPG program guide support.",
            )
            UnifiedFeatureItem(
                title = "HSV Theme Engine & Biometrics",
                description = "Interactive color picker with Hue/Saturation/Value spectrum and fingerprint/biometric security for profiles.",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.nuvio.colors.textMuted.copy(alpha = 0.15f)),
            )

            // Section 2: Luqman / Upstream Core
            Text(
                text = "🚀 Official Core Features (by Luqman)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.nuvio.colors.accent,
            )

            UnifiedFeatureItem(
                title = "Stremio Addon Ecosystem",
                description = "Integrated Stremio addon resolver supporting both official and community addons for expansive catalog browsing and streaming.",
            )
            UnifiedFeatureItem(
                title = "Compose Multiplatform UI",
                description = "Fluid, responsive native user interface engineered for high performance on Android and iOS devices.",
            )
            UnifiedFeatureItem(
                title = "Dual Media Engines (ExoPlayer & MPV)",
                description = "Hardware-accelerated video decoding with intuitive swipe gestures for volume, brightness, and seek scrubbing.",
            )
            UnifiedFeatureItem(
                title = "Multi-Profile Management",
                description = "Individual user profiles with separate watch histories, continuous playback, and personalized settings.",
            )
            UnifiedFeatureItem(
                title = "Watch Party & SyncPlay",
                description = "Host or join synchronized watch rooms to enjoy movies and TV shows together with friends in real time.",
            )
            UnifiedFeatureItem(
                title = "Unified Search & Discovery",
                description = "Global content search with genre filtering, trending feeds, popular lists, and curated recommendations.",
            )
            UnifiedFeatureItem(
                title = "Automated Intro & Outro Skipping",
                description = "IntroDB skip integration to automatically detect and skip show opening and closing themes.",
            )

            if (latestReleaseWithNotes != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.nuvio.colors.textMuted.copy(alpha = 0.15f)),
                )

                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.nuvio.colors.textSecondary,
                    ),
                    onClick = { showGithubChangelog = !showGithubChangelog },
                ) {
                    Text(
                        text = if (showGithubChangelog) "Hide GitHub Changelog" else "View GitHub Release Notes (${latestReleaseWithNotes.version})",
                    )
                }

                if (showGithubChangelog) {
                    ReleaseNotes(
                        release = latestReleaseWithNotes,
                        expanded = true,
                        onToggle = {},
                        allowToggle = false,
                        showGithub = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedFeatureItem(
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.nuvio.colors.accent,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ReleaseNotes(
    release: WhatsNewRelease,
    expanded: Boolean,
    onToggle: () -> Unit,
    allowToggle: Boolean,
    showGithub: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val visibleNotes = if (expanded) release.notes else previewNotes(release.notes)
    val contributors = release.notes
        .mapNotNull { it.authorLogin?.takeIf(String::isNotBlank) }
        .distinct()
    val showContributors = contributors.isNotEmpty() && (expanded || !allowToggle)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        if (visibleNotes.isEmpty()) {
            Text(
                text = stringResource(Res.string.whats_new_no_release_notes),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
            )
        } else {
            NOTE_CATEGORY_ORDER.forEach { category ->
                val notes = visibleNotes.filter { it.category == category }
                if (notes.isEmpty()) return@forEach

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                ) {
                    Text(
                        text = stringResource(noteCategoryResource(category)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.colors.textMuted,
                    )
                    notes.forEach { note ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.colors.textPrimary,
                            )
                            Text(
                                text = WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay(note.text),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.colors.textPrimary,
                            )
                        }
                    }
                    }
                }
            }
        }

        if (showContributors) {
            Text(
                text = stringResource(
                    Res.string.whats_new_contributor_credit,
                    contributors.joinToString(" · @"),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }

        val releaseUrl = release.releaseUrl?.takeIf { showGithub }
        if (allowToggle || releaseUrl != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (allowToggle) {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = tokens.colors.textPrimary,
                                ),
                                onClick = onToggle,
                            ) {
                                Text(
                                    text = if (expanded) {
                                        stringResource(Res.string.whats_new_hide_changes)
                                    } else {
                                        stringResource(
                                            Res.string.whats_new_show_all_changes,
                                            release.notes.size,
                                        )
                                    },
                                )
                            }
                        }
                        releaseUrl?.let { url ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = tokens.colors.textSecondary,
                                ),
                                onClick = { uriHandler.openUri(url) },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(NuvioTokens.Icon.sm),
                                )
                                Spacer(modifier = Modifier.size(NuvioTokens.Space.s4))
                                Text(stringResource(Res.string.whats_new_open_github))
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (allowToggle) {
                            TextButton(
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = tokens.colors.textPrimary,
                                ),
                                onClick = onToggle,
                            ) {
                                Text(
                                    text = if (expanded) {
                                        stringResource(Res.string.whats_new_hide_changes)
                                    } else {
                                        stringResource(
                                            Res.string.whats_new_show_all_changes,
                                            release.notes.size,
                                        )
                                    },
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        releaseUrl?.let { url ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = tokens.colors.textSecondary,
                                ),
                                onClick = { uriHandler.openUri(url) },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(NuvioTokens.Icon.sm),
                                )
                                Spacer(modifier = Modifier.size(NuvioTokens.Space.s4))
                                Text(stringResource(Res.string.whats_new_open_github))
                            }
                        }
                    }
                }
            }
        }
    }

private fun previewNotes(notes: List<WhatsNewNote>): List<WhatsNewNote> {
    if (notes.size <= PREVIEW_MIN_NOTES) return notes

    val preview = ArrayList<WhatsNewNote>(minOf(notes.size, PREVIEW_MAX_NOTES))
    var visibleCharacters = 0

    for (note in notes) {
        if (preview.size >= PREVIEW_MAX_NOTES) break

        val noteCharacters = note.text.trim().length
        if (preview.size >= PREVIEW_MIN_NOTES &&
            visibleCharacters + noteCharacters > PREVIEW_NOTE_CHAR_LIMIT
        ) {
            break
        }

        preview += note
        visibleCharacters += noteCharacters
    }

    return preview
}

private fun noteCategoryResource(category: WhatsNewNoteCategory) = when (category) {
    WhatsNewNoteCategory.FEATURES -> Res.string.whats_new_category_features
    WhatsNewNoteCategory.FIXES -> Res.string.whats_new_category_fixes
    WhatsNewNoteCategory.PERFORMANCE -> Res.string.whats_new_category_performance
    WhatsNewNoteCategory.LOCALIZATION -> Res.string.whats_new_category_localization
    WhatsNewNoteCategory.OTHER -> Res.string.whats_new_category_other
}

private fun releaseDate(release: WhatsNewRelease): String? =
    release.publishedAt
        ?.takeIf { it.isNotBlank() }
        ?.let(::formatReleaseDateForDisplay)