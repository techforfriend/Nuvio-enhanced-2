package com.nuvio.app.features.vega

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VegaManifestItem(
    @SerialName("display_name") val displayName: String,
    val value: String,
    val version: String? = null,
    val icon: String? = null,
    val type: String? = null,
    val disabled: Boolean = false,
    @SerialName("hasSettings") val hasSettings: Boolean = false,
)

@Serializable
data class VegaProviderItem(
    val repositoryUrl: String,
    val displayName: String,
    val value: String,
    val version: String? = null,
    val icon: String? = null,
    val type: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class VegaRepositoryItem(
    val url: String,
    val name: String,
    val providers: List<VegaProviderItem> = emptyList(),
    val enabled: Boolean = true,
    val lastUpdatedEpochMs: Long = 0L,
    val errorMessage: String? = null,
)

data class VegaUiState(
    val vegaEnabled: Boolean = true,
    val repositories: List<VegaRepositoryItem> = emptyList(),
    val providers: List<VegaProviderItem> = emptyList(),
    val isRefreshing: Boolean = false,
)

sealed interface AddVegaRepositoryResult {
    data class Success(val repository: VegaRepositoryItem) : AddVegaRepositoryResult
    data class Error(val message: String) : AddVegaRepositoryResult
}

@Serializable
internal data class StoredVegaState(
    val vegaEnabled: Boolean = true,
    val repositories: List<VegaRepositoryItem> = emptyList(),
)

@Serializable
data class VegaStreamResult(
    val server: String = "Vega",
    val link: String,
    val type: String? = null,
    val quality: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<VegaSubtitleResult>? = null,
)

@Serializable
data class VegaSubtitleResult(
    val title: String? = null,
    val language: String? = null,
    val type: String? = null,
    val uri: String,
)
