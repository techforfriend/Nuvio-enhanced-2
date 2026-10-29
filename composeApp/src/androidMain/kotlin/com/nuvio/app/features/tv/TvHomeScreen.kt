package com.nuvio.app.features.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import coil3.compose.AsyncImage
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.isWaitingForFirstEnabledManifest
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview

/**
 * TV-style Home hero: a full-width backdrop banner (the phone Home's
 * small "Featured" rail is not a TV hero), with the title, release info
 * and a Details button the D-pad lands on first. Reshaped-style, the
 * billboard follows the focused card: focusing any rail card swaps the
 * banner to that title with its details expanded underneath.
 */
@Composable
private fun TvHomeHero(
    item: MetaPreview,
    onDetails: () -> Unit,
    detailsFocusRequester: FocusRequester?,
) {
    val backdrop = item.banner ?: item.poster
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp),
    ) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val infoLine = listOfNotNull(
                item.releaseInfo,
                item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                item.imdbRating?.let { "IMDb $it" },
            ).joinToString(" • ")
            if (infoLine.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = infoLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            TvControlButton(
                label = "Details",
                onClick = onDetails,
                focusRequester = detailsFocusRequester,
            )
        }
    }
}
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
    onOpenLiveTv: () -> Unit,
) {
    val homeState by HomeRepository.uiState.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val enabledAddons = addonsState.addons.enabledAddons()
    val firstCardFocusRequester = remember { FocusRequester() }
    // Reshaped-style Netflix billboard: follows the focused rail card.
    // Null means "first hero item" until a card takes focus.
    var billboardItem by remember { mutableStateOf<MetaPreview?>(null) }

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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TvPillMenu(
                                onOpenSearch = onOpenSearch,
                                onOpenDownloads = onOpenDownloads,
                                onOpenParty = onOpenParty,
                                onOpenSettings = onOpenSettings,
                                onOpenLiveTv = onOpenLiveTv,
                            )
                        }
                    }
                    val hasHero = homeState.heroItems.isNotEmpty()
                    if (hasHero) {
                        val hero = billboardItem ?: homeState.heroItems.first()
                        item {
                            TvHomeHero(
                                item = hero,
                                onDetails = { onItemClick(hero) },
                                detailsFocusRequester = firstCardFocusRequester,
                            )
                        }
                        item {
                            TvHomeRail(
                                title = "Featured",
                                subtitle = "",
                                items = homeState.heroItems,
                                onItemClick = onItemClick,
                                onItemFocused = { billboardItem = it },
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
                            onItemFocused = { billboardItem = it },
                        )
                    }
                }
            }
        }
    }
}
