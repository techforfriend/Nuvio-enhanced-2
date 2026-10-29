package com.nuvio.app.features.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.isWaitingForFirstEnabledManifest
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview

/**
 * Phase 2 TV Home: rails of real catalog data from [HomeRepository].
 *
 * The phone Home screen never runs on TV (TvMainActivity swaps the root),
 * so this screen triggers the same load the phone path does: once addon
 * manifests are ready, sync catalogs and refresh Home. Card clicks are
 * accepted but do nothing yet — Details is Phase 4.
 */
@Composable
fun TvHomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenParty: () -> Unit,
) {
    val homeState by HomeRepository.uiState.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val enabledAddons = addonsState.addons.enabledAddons()
    val firstCardFocusRequester = remember { FocusRequester() }

    LaunchedEffect(addonsState.addons) {
        if (enabledAddons.isWaitingForFirstEnabledManifest()) return@LaunchedEffect
        HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
        HomeRepository.refresh(enabledAddons)
    }

    val sectionsLoaded = homeState.sections.isNotEmpty() || homeState.heroItems.isNotEmpty()
    LaunchedEffect(sectionsLoaded) {
        if (sectionsLoaded) firstCardFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        when {
            homeState.isLoading && !sectionsLoaded -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            homeState.errorMessage != null && !sectionsLoaded -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        text = homeState.errorMessage ?: "Something went wrong.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { HomeRepository.refresh(enabledAddons, force = true) }) {
                        Text("Retry")
                    }
                }
            }
            !sectionsLoaded -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Nothing found — check addons or catalog settings.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Nuvio Enhanced TV",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            TvControlButton(label = "Search", onClick = onOpenSearch)
                            Spacer(Modifier.width(12.dp))
                            TvControlButton(label = "Downloads", onClick = onOpenDownloads)
                            Spacer(Modifier.width(12.dp))
                            TvControlButton(label = "Watch Party", onClick = onOpenParty)
                            Spacer(Modifier.width(12.dp))
                            TvControlButton(label = "Settings", onClick = onOpenSettings)
                        }
                    }
                    val hasHero = homeState.heroItems.isNotEmpty()
                    if (hasHero) {
                        item {
                            TvHomeRail(
                                title = "Featured",
                                subtitle = "",
                                items = homeState.heroItems,
                                onItemClick = onItemClick,
                                firstCardFocusRequester = firstCardFocusRequester,
                            )
                        }
                    }
                    items(homeState.sections, key = { it.key }) { section ->
                        TvHomeRail(
                            title = section.title,
                            subtitle = section.subtitle,
                            items = section.items,
                            onItemClick = onItemClick,
                            firstCardFocusRequester =
                                if (!hasHero && section == homeState.sections.firstOrNull()) firstCardFocusRequester else null,
                        )
                    }
                }
            }
        }
    }
}
