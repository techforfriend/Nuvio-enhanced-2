package com.nuvio.app.features.vega

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamItem

internal actual object VegaBridge {
    actual suspend fun getCatalogCategories(provider: VegaProviderItem): List<VegaCatalogCategory> = emptyList()
    actual suspend fun getPosts(provider: VegaProviderItem, filter: String, page: Int): List<VegaPost> = emptyList()
    actual suspend fun getSearchPosts(provider: VegaProviderItem, query: String, page: Int): List<VegaPost> = emptyList()
    actual suspend fun getMetaDetails(provider: VegaProviderItem, link: String): MetaDetails? = null
    actual suspend fun getStreams(provider: VegaProviderItem, link: String, type: String): List<StreamItem> = emptyList()
}
