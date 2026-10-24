package com.nuvio.app.features.cloud

import com.nuvio.app.core.storage.AppFileCache
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.debrid.DebridProvider
import com.nuvio.app.features.debrid.DebridProviders
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val cloudLibraryCacheJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class CachedCloudLibraryFile(
    val id: String? = null,
    val name: String,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val playable: Boolean = true,
)

@Serializable
private data class CachedCloudLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val status: String? = null,
    val sizeBytes: Long? = null,
    val progressFraction: Float? = null,
    val files: List<CachedCloudLibraryFile> = emptyList(),
)

@Serializable
private data class CachedCloudLibraryProvider(
    val providerId: String,
    val items: List<CachedCloudLibraryItem> = emptyList(),
)

@Serializable
private data class CachedCloudLibraryPayload(
    val signature: List<String>,
    val providers: List<CachedCloudLibraryProvider> = emptyList(),
)

internal object CloudLibraryCacheStore {
    private fun path(): String = "cloud_library/${ProfileScopedKey.of("items")}.json"

    fun load(signature: List<String>): List<CloudLibraryProviderState>? = runCatching {
        if (signature.isEmpty()) return null
        val payload = AppFileCache.read(path())?.takeIf(String::isNotBlank) ?: return null
        val decoded = cloudLibraryCacheJson.decodeFromString<CachedCloudLibraryPayload>(payload)
        if (decoded.signature != signature) return null
        decoded.providers.mapNotNull { cachedProvider ->
            val provider = DebridProviders.byId(cachedProvider.providerId) ?: return@mapNotNull null
            CloudLibraryProviderState(
                provider = provider,
                items = cachedProvider.items.mapNotNull { it.toCloudLibraryItem(provider) },
            )
        }
    }.getOrNull()

    fun save(signature: List<String>, providers: List<CloudLibraryProviderState>) {
        runCatching {
            if (signature.isEmpty()) {
                AppFileCache.write(path(), "")
                return
            }
            val payload = CachedCloudLibraryPayload(
                signature = signature,
                providers = providers.map { providerState ->
                    CachedCloudLibraryProvider(
                        providerId = providerState.providerId,
                        items = providerState.items.map { it.toCachedCloudLibraryItem() },
                    )
                },
            )
            AppFileCache.write(path(), cloudLibraryCacheJson.encodeToString(payload))
        }
    }
}

private fun CloudLibraryItem.toCachedCloudLibraryItem(): CachedCloudLibraryItem =
    CachedCloudLibraryItem(
        id = id,
        type = type.name,
        name = name,
        status = status,
        sizeBytes = sizeBytes,
        progressFraction = progressFraction,
        files = files.map { file ->
            CachedCloudLibraryFile(
                id = file.id,
                name = file.name,
                sizeBytes = file.sizeBytes,
                mimeType = file.mimeType,
                playable = file.playable,
            )
        },
    )

private fun CachedCloudLibraryItem.toCloudLibraryItem(provider: DebridProvider): CloudLibraryItem? {
    val itemType = CloudLibraryItemType.entries.firstOrNull { it.name == type } ?: return null
    return CloudLibraryItem(
        providerId = provider.id,
        providerName = provider.displayName,
        id = id,
        type = itemType,
        name = name,
        status = status,
        sizeBytes = sizeBytes,
        progressFraction = progressFraction,
        files = files.map { file ->
            CloudLibraryFile(
                id = file.id,
                name = file.name,
                sizeBytes = file.sizeBytes,
                mimeType = file.mimeType,
                playable = file.playable,
            )
        },
    )
}
