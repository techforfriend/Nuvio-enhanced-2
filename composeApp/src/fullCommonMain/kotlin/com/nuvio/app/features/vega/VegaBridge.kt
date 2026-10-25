package com.nuvio.app.features.vega

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamItem

internal actual object VegaBridge {
    actual suspend fun getCatalogCategories(provider: VegaProviderItem): List<VegaCatalogCategory> {
        val script = VegaRepository.getOrFetchScript(provider, "catalog.js")
            ?: return listOf(VegaCatalogCategory(title = "Featured", filter = ""))
        return VegaRuntime.executeCatalog(provider, script)
    }

    actual suspend fun getPosts(provider: VegaProviderItem, filter: String, page: Int): List<VegaPost> {
        val script = VegaRepository.getOrFetchScript(provider, "posts.js")
            ?: return emptyList()
        return VegaRuntime.executePosts(provider, script, filter, page)
    }

    actual suspend fun getSearchPosts(provider: VegaProviderItem, query: String, page: Int): List<VegaPost> {
        val script = VegaRepository.getOrFetchScript(provider, "posts.js")
            ?: return emptyList()
        return VegaRuntime.executeSearchPosts(provider, script, query, page)
    }

    actual suspend fun getMetaDetails(provider: VegaProviderItem, link: String): MetaDetails? {
        val metaScript = VegaRepository.getOrFetchScript(provider, "meta.js") ?: return null
        val episodesScript = VegaRepository.getOrFetchScript(provider, "episodes.js")
        val metaResult = VegaRuntime.executeMeta(provider, metaScript, episodesScript, link)
        return metaResult?.toMetaDetails(provider.value, link)
    }

    actual suspend fun getStreams(provider: VegaProviderItem, link: String, type: String): List<StreamItem> {
        val script = VegaRepository.getOrFetchScript(provider, "stream.js") ?: return emptyList()
        return VegaRuntime.executeDirectStream(provider, script, link, type)
    }
}
