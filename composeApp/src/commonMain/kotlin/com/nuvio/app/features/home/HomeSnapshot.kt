package com.nuvio.app.features.home

import com.nuvio.app.core.storage.AppFileCache
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val homeSnapshotJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class SnapshotMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val posterShape: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rawReleaseDate: String? = null,
    val popularity: Double? = null,
    val voteCount: Int? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val rawPosterUrl: String? = null,
    val landscapePoster: String? = null,
    val rawLandscapePosterUrl: String? = null,
)

@Serializable
private data class SnapshotTarget(
    val kind: String,
    val contentType: String,
    val supportsPagination: Boolean = false,
    val manifestUrl: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    val connectionId: String? = null,
    val libraryId: String? = null,
    val collectionId: String? = null,
)

@Serializable
private data class SnapshotSection(
    val cacheKey: String,
    val key: String,
    val title: String,
    val subtitle: String,
    val addonName: String,
    val target: SnapshotTarget,
    val items: List<SnapshotMeta>,
    val availableItemCount: Int,
    val hasMore: Boolean = false,
)

@Serializable
private data class SnapshotPayload(
    val profileKey: String,
    val savedAtMillis: Long,
    val sections: List<SnapshotSection> = emptyList(),
    val heroItems: List<SnapshotMeta> = emptyList(),
)

internal class HomeSnapshot(
    val profileKey: String,
    val sections: List<Pair<String, HomeCatalogSection>>,
    val heroItems: List<MetaPreview>,
)

internal object HomeSnapshotStore {
    fun currentProfileKey(): String? {
        val profile = ProfileRepository.state.value.activeProfile ?: return null
        return "${profile.userId}|${profile.id}|${profile.profileIndex}"
    }

    private fun pathFor(profileKey: String): String =
        "home/${profileKey.length}_${profileKey.hashCode().toUInt()}.json"

    fun load(profileKey: String): HomeSnapshot? = runCatching {
        val payload = AppFileCache.read(pathFor(profileKey))?.takeIf(String::isNotBlank) ?: return null
        val decoded = homeSnapshotJson.decodeFromString<SnapshotPayload>(payload)
        if (decoded.profileKey != profileKey) return null
        HomeSnapshot(
            profileKey = profileKey,
            sections = decoded.sections.mapNotNull { section ->
                val target = section.target.toTarget() ?: return@mapNotNull null
                section.cacheKey to HomeCatalogSection(
                    key = section.key,
                    title = section.title,
                    subtitle = section.subtitle,
                    addonName = section.addonName,
                    target = target,
                    items = section.items.map { it.toMetaPreview() },
                    availableItemCount = section.availableItemCount,
                    hasMore = section.hasMore,
                )
            },
            heroItems = decoded.heroItems.map { it.toMetaPreview() },
        )
    }.getOrNull()

    fun save(
        profileKey: String,
        savedAtMillis: Long,
        sections: List<Pair<String, HomeCatalogSection>>,
        heroItems: List<MetaPreview>,
    ) {
        runCatching {
            val payload = SnapshotPayload(
                profileKey = profileKey,
                savedAtMillis = savedAtMillis,
                sections = sections.mapNotNull { (cacheKey, section) ->
                    val target = section.target.toSnapshotTarget() ?: return@mapNotNull null
                    SnapshotSection(
                        cacheKey = cacheKey,
                        key = section.key,
                        title = section.title,
                        subtitle = section.subtitle,
                        addonName = section.addonName,
                        target = target,
                        items = section.items.map { it.toSnapshotMeta() },
                        availableItemCount = section.availableItemCount,
                        hasMore = section.hasMore,
                    )
                },
                heroItems = heroItems.map { it.toSnapshotMeta() },
            )
            AppFileCache.write(pathFor(profileKey), homeSnapshotJson.encodeToString(payload))
        }
    }
}

private fun MetaPreview.toSnapshotMeta(): SnapshotMeta = SnapshotMeta(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = banner,
    logo = logo,
    posterShape = posterShape.name,
    description = description,
    releaseInfo = releaseInfo,
    rawReleaseDate = rawReleaseDate,
    popularity = popularity,
    voteCount = voteCount,
    imdbRating = imdbRating,
    genres = genres,
    rawPosterUrl = rawPosterUrl,
    landscapePoster = landscapePoster,
    rawLandscapePosterUrl = rawLandscapePosterUrl,
)

private fun SnapshotMeta.toMetaPreview(): MetaPreview = MetaPreview(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = banner,
    logo = logo,
    posterShape = PosterShape.entries.firstOrNull { it.name == posterShape } ?: PosterShape.Poster,
    description = description,
    releaseInfo = releaseInfo,
    rawReleaseDate = rawReleaseDate,
    popularity = popularity,
    voteCount = voteCount,
    imdbRating = imdbRating,
    genres = genres,
    rawPosterUrl = rawPosterUrl,
    landscapePoster = landscapePoster,
    rawLandscapePosterUrl = rawLandscapePosterUrl,
)

private fun CatalogTarget.toSnapshotTarget(): SnapshotTarget? = when (this) {
    is CatalogTarget.Addon -> SnapshotTarget(
        kind = "addon",
        contentType = contentType,
        supportsPagination = supportsPagination,
        manifestUrl = manifestUrl,
        catalogId = catalogId,
        genre = genre,
    )
    is CatalogTarget.Server -> SnapshotTarget(
        kind = "server",
        contentType = contentType,
        connectionId = connectionId,
        libraryId = libraryId,
        collectionId = collectionId,
    )
    else -> null
}

private fun SnapshotTarget.toTarget(): CatalogTarget? = when (kind) {
    "addon" -> CatalogTarget.Addon(
        manifestUrl = manifestUrl ?: return null,
        contentType = contentType,
        catalogId = catalogId ?: return null,
        genre = genre,
        supportsPagination = supportsPagination,
    )
    "server" -> CatalogTarget.Server(
        connectionId = connectionId ?: return null,
        libraryId = libraryId,
        contentType = contentType,
        collectionId = collectionId,
    )
    else -> null
}
