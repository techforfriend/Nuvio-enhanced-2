package com.nuvio.app.features.vega

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamItem

internal expect object VegaBridge {
    suspend fun getCatalogCategories(provider: VegaProviderItem): List<VegaCatalogCategory>
    suspend fun getPosts(provider: VegaProviderItem, filter: String, page: Int): List<VegaPost>
    suspend fun getSearchPosts(provider: VegaProviderItem, query: String, page: Int): List<VegaPost>
    suspend fun getMetaDetails(provider: VegaProviderItem, link: String): MetaDetails?
    suspend fun getStreams(provider: VegaProviderItem, link: String, type: String): List<StreamItem>
}
