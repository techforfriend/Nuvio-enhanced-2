package com.nuvio.app.features.vega

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VegaNuvioAdapterTest {

    @Test
    fun parseVegaRouteIdRoundTrips() {
        val providerId = "vega"
        val link = "https://new1.vegamovies.lease/download-money-heist-series"
        val routeId = vegaRouteId(providerId, link)
        val parsed = parseVegaRouteId(routeId)
        assertNotNull(parsed)
        assertEquals(providerId, parsed.providerId)
        assertEquals(link, parsed.link)
        assertEquals(link, parsed.cleanLink())
    }

    @Test
    fun cleanLinkStripsFragmentsAndSeasonEpisodeSuffixes() {
        val routeWithSuffix = VegaRouteData("vega", "https://new1.vegamovies.lease/download-show:1:2")
        assertEquals("https://new1.vegamovies.lease/download-show", routeWithSuffix.cleanLink())

        val routeWithFragment = VegaRouteData("vega", "https://new1.vegamovies.lease/download-movie#quality")
        assertEquals("https://new1.vegamovies.lease/download-movie", routeWithFragment.cleanLink())

        val routeWithBoth = VegaRouteData("vega", "https://new1.vegamovies.lease/download-movie:2:3#part")
        assertEquals("https://new1.vegamovies.lease/download-movie:2:3", routeWithBoth.cleanLink())
    }

    @Test
    fun parseVegaRouteIdReturnsNullForInvalid() {
        assertNull(parseVegaRouteId("invalid:id"))
        assertNull(parseVegaRouteId("vega:invalid"))
        assertNull(parseVegaRouteId(""))
    }

    @Test
    fun toMetaPreviewDetectsSeriesAndMovie() {
        val moviePost = VegaPost(
            title = "Inception (2010) Full Movie",
            link = "https://new1.vegamovies.lease/download-inception",
        )
        val seriesPost = VegaPost(
            title = "Breaking Bad (2008) Season 1",
            link = "https://new1.vegamovies.lease/download-breaking-bad",
        )
        val moviePreview = moviePost.toMetaPreview("vega")
        val seriesPreview = seriesPost.toMetaPreview("vega")

        assertEquals("movie", moviePreview.type)
        assertEquals("series", seriesPreview.type)
    }

    @Test
    fun toMetaDetailsConvertsDirectLinksAndEpisodes() {
        val movieResult = VegaMetaResult(
            title = "Inception",
            type = "movie",
            linkList = listOf(
                VegaLinkGroup(
                    title = "1080p",
                    quality = "1080p",
                    directLinks = listOf(
                        VegaDirectLink(title = "Movie", link = "https://nexdrive.beer/123"),
                    ),
                ),
            ),
        )
        val movieDetails = movieResult.toMetaDetails("vega", "https://new1.vegamovies.lease/download-inception")
        assertEquals("movie", movieDetails.type)
        assertEquals(1, movieDetails.videos.size)
        assertEquals("vega:4:vegahttps://nexdrive.beer/123", movieDetails.videos.first().id)

        val seriesResult = VegaMetaResult(
            title = "Loki",
            type = "series",
            episodes = listOf(
                VegaEpisode(
                    title = "Glorious Purpose",
                    link = "{\"hubcloud\":\"https://vcloud.beer/1\",\"vegadrive\":\"https://vegadrive.app/1\"}",
                    season = 1,
                    episode = 1,
                ),
            ),
        )
        val seriesDetails = seriesResult.toMetaDetails("vega", "https://new1.vegamovies.lease/download-loki")
        assertEquals("series", seriesDetails.type)
        assertEquals(1, seriesDetails.videos.size)
        assertEquals(1, seriesDetails.videos.first().season)
        assertEquals(1, seriesDetails.videos.first().episode)
    }
}
