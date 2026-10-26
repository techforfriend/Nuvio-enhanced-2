package com.nuvio.app.features.vega

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object VegaMergedPostsRepository {
    private val linkToGroupedPosts = linkedMapOf<String, MutableList<VegaPost>>()
    private val keyToPrimaryLink = linkedMapOf<String, String>()
    private val mutex = Mutex()

    suspend fun register(providerId: String, dedupeKey: String, post: VegaPost): String = mutex.withLock {
        val key = "$providerId:$dedupeKey"
        val existingPrimary = keyToPrimaryLink[key]
        if (existingPrimary == null) {
            keyToPrimaryLink[key] = post.link
            val list = linkToGroupedPosts.getOrPut("$providerId:${post.link}") { mutableListOf() }
            if (list.none { it.link == post.link }) {
                list.add(post)
            }
            post.link
        } else {
            val list = linkToGroupedPosts.getOrPut("$providerId:$existingPrimary") { mutableListOf() }
            if (list.none { it.link == post.link }) {
                list.add(post)
            }
            existingPrimary
        }
    }

    suspend fun getAlternatePosts(providerId: String, link: String): List<VegaPost> = mutex.withLock {
        linkToGroupedPosts["$providerId:$link"]?.toList() ?: emptyList()
    }
}
