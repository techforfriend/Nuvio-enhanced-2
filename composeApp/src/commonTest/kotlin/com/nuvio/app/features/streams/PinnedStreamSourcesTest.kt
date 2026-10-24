package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinnedStreamSourcesTest {

    @Test
    fun `pinning one scraper lifts only that scraper out of its plugin group`() {
        val groups = listOf(
            group("addon:usenet", "Usenet Streamer", null),
            group("plugin-repo:d3adly", "D3adlyRocket", "NetMirror", "SomethingElse"),
        )
        val netMirror = PinnedStreamSourcesRepository.sourceKeyFor("plugin-repo:d3adly", "NetMirror")

        val split = splitPinnedSources(groups, listOf(netMirror))

        assertEquals(listOf("NetMirror"), split.pinnedGroups.map { it.addonName })
        assertEquals(listOf("Usenet Streamer", "D3adlyRocket"), split.remainingGroups.map { it.addonName })
        assertEquals(
            listOf("SomethingElse"),
            split.remainingGroups.last().streams.map { it.sourceName },
        )
    }

    @Test
    fun `pinning an addon without sub-sources lifts the whole addon`() {
        val groups = listOf(
            group("addon:usenet", "Usenet Streamer", null),
            group("addon:torbox", "TorBox", null),
        )
        val usenet = PinnedStreamSourcesRepository.sourceKeyFor("addon:usenet", null)

        val split = splitPinnedSources(groups, listOf(usenet))

        assertEquals(listOf("Usenet Streamer"), split.pinnedGroups.map { it.addonName })
        assertEquals(listOf("TorBox"), split.remainingGroups.map { it.addonName })
    }

    @Test
    fun `pinned sources come out in the order they were pinned`() {
        val groups = listOf(
            group("plugin-repo:d3adly", "D3adlyRocket", "NetMirror", "Zoechip"),
        )
        val netMirror = PinnedStreamSourcesRepository.sourceKeyFor("plugin-repo:d3adly", "NetMirror")
        val zoechip = PinnedStreamSourcesRepository.sourceKeyFor("plugin-repo:d3adly", "Zoechip")

        val split = splitPinnedSources(groups, listOf(zoechip, netMirror))

        assertEquals(listOf("Zoechip", "NetMirror"), split.pinnedGroups.map { it.addonName })
        assertTrue(split.remainingGroups.isEmpty())
    }

    @Test
    fun `a group still loading survives even when every stream it has is pinned away`() {
        val groups = listOf(
            group("plugin-repo:d3adly", "D3adlyRocket", "NetMirror").copy(isLoading = true),
        )
        val netMirror = PinnedStreamSourcesRepository.sourceKeyFor("plugin-repo:d3adly", "NetMirror")

        val split = splitPinnedSources(groups, listOf(netMirror))

        assertEquals(listOf("NetMirror"), split.pinnedGroups.map { it.addonName })
        assertEquals(listOf("D3adlyRocket"), split.remainingGroups.map { it.addonName })
        assertTrue(split.remainingGroups.single().streams.isEmpty())
    }

    @Test
    fun `no pins leaves the groups untouched`() {
        val groups = listOf(group("addon:usenet", "Usenet Streamer", null))

        val split = splitPinnedSources(groups, emptyList())

        assertTrue(split.pinnedGroups.isEmpty())
        assertEquals(groups, split.remainingGroups)
    }

    @Test
    fun `sourceKeyFor falls back to the addon id when a stream has no source name`() {
        assertEquals("addon:usenet", PinnedStreamSourcesRepository.sourceKeyFor("addon:usenet", null))
        assertEquals("addon:usenet", PinnedStreamSourcesRepository.sourceKeyFor("addon:usenet", "  "))
        assertEquals(
            "plugin-repo:d3adly|NetMirror",
            PinnedStreamSourcesRepository.sourceKeyFor("plugin-repo:d3adly", "NetMirror"),
        )
    }

    private fun group(addonId: String, addonName: String, vararg sources: String?) = AddonStreamGroup(
        addonName = addonName,
        addonId = addonId,
        streams = sources.map { source ->
            StreamItem(
                name = source ?: addonName,
                url = "https://example.com/${source ?: addonName}.mkv",
                sourceName = source,
                addonName = addonName,
                addonId = addonId,
            )
        },
    )
}
