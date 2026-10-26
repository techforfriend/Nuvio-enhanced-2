package com.nuvio.app.features.vega

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VegaSettingsSection() {
    LaunchedEffect(Unit) {
        VegaRepository.initialize()
    }

    val state by VegaRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var repositoryUrl by rememberSaveable { mutableStateOf("") }
    var isAddingRepository by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    val visibleProviders = remember(state.providers, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            state.providers
        } else {
            state.providers.filter { provider ->
                provider.displayName.lowercase().contains(query) ||
                    provider.value.lowercase().contains(query) ||
                    (provider.type != null && provider.type.lowercase().contains(query))
            }
        }
    }

    val hasDefaultRepo = remember(state.repositories) {
        state.repositories.any { it.url.contains("vega-providers") }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioSectionLabel("Vega Providers")
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                .border(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp),
        ) {
            Text(
                text = "BETA",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioInfoBadge(text = "${state.repositories.size} Repositories")
            NuvioInfoBadge(text = "${state.providers.size} Providers")
            NuvioInfoBadge(text = "${state.providers.count { it.enabled }} Active")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Vega JavaScript providers allow streaming movies and series directly inside Nuvio. " +
                "Manage external repositories and enable or disable individual scrapers below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Vega Provider Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Include enabled Vega scrapers during playback link resolution",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.vegaEnabled,
                onCheckedChange = { VegaRepository.setVegaEnabled(it) },
            )
        }
    }

    NuvioSurfaceCard {
        Text(
            text = "Add Vega Repository",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        NuvioInputField(
            value = repositoryUrl,
            onValueChange = {
                repositoryUrl = it
                message = null
            },
            placeholder = "Shortcode, username, or manifest URL (e.g. vega-org)",
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioPrimaryButton(
                text = if (isAddingRepository) "Adding..." else "Add Repository",
                enabled = repositoryUrl.isNotBlank() && !isAddingRepository,
                onClick = {
                    val requested = repositoryUrl.trim()
                    isAddingRepository = true
                    message = null
                    scope.launch {
                        when (val result = VegaRepository.addRepository(requested)) {
                            is AddVegaRepositoryResult.Success -> {
                                repositoryUrl = ""
                                message = "Repository added: ${result.repository.name} (${result.repository.providers.size} providers)"
                            }
                            is AddVegaRepositoryResult.Error -> {
                                message = result.message
                            }
                        }
                        isAddingRepository = false
                    }
                },
            )
        }

        if (!hasDefaultRepo) {
            Spacer(Modifier.height(10.dp))
            NuvioPrimaryButton(
                text = "Install Official Vega Repository",
                enabled = !isAddingRepository,
                onClick = {
                    isAddingRepository = true
                    message = null
                    scope.launch {
                        when (val result = VegaRepository.addRepository(VegaRepository.DEFAULT_REPO_URL)) {
                            is AddVegaRepositoryResult.Success -> {
                                message = "Official Vega repo added with ${result.repository.providers.size} providers!"
                            }
                            is AddVegaRepositoryResult.Error -> {
                                message = result.message
                            }
                        }
                        isAddingRepository = false
                    }
                },
            )
        }

        message?.let { feedback ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = feedback,
                style = MaterialTheme.typography.bodySmall,
                color = if (feedback.contains("failed", ignoreCase = true) || feedback.contains("error", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }

    if (state.repositories.isNotEmpty()) {
        NuvioSectionLabel("Installed Repositories (${state.repositories.size})")
        state.repositories.forEach { repo ->
            NuvioSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = repo.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${repo.providers.size} providers • ${repo.url}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        repo.errorMessage?.let { err ->
                            Text(
                                text = "Error: $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(
                        onClick = { VegaRepository.refreshRepository(repo.url) },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { VegaRepository.removeRepository(repo.url) },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (state.providers.isNotEmpty()) {
        NuvioSectionLabel("Providers (${visibleProviders.size})")
        NuvioSurfaceCard {
            NuvioInputField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search providers (e.g. MultiStream, Drive, FlixHQ)",
            )
        }

        visibleProviders.forEach { provider ->
            VegaProviderCard(provider = provider)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VegaProviderCard(provider: VegaProviderItem) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!provider.icon.isNullOrBlank()) {
                AsyncImage(
                    model = provider.icon,
                    contentDescription = provider.displayName,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    provider.type?.let { NuvioInfoBadge(text = it) }
                    provider.version?.let { NuvioInfoBadge(text = "v$it") }
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = provider.enabled,
                onCheckedChange = { isChecked ->
                    VegaRepository.setProviderEnabled(
                        repositoryUrl = provider.repositoryUrl,
                        providerValue = provider.value,
                        enabled = isChecked,
                    )
                },
            )
        }
    }
}
