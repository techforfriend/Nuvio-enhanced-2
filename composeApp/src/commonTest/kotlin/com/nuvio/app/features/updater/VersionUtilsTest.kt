package com.nuvio.app.features.updater

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class VersionUtilsTest {
    @Test
    fun `stable release is newer than its prerelease`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-rc.2"))
    }

    @Test
    fun `beta identifiers use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.10", "1.1.0-beta.9"))
    }

    @Test
    fun `prerelease for a later minor is newer than current stable`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.1", "1.0.3"))
    }

    @Test
    fun `stable channel patch is not newer than later minor beta`() {
        assertFalse(VersionUtils.isRemoteNewer("1.0.4", "1.1.0-beta.2"))
    }

    @Test
    fun `version prefix and build metadata do not affect precedence`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.0.0+18", "1.0.0+17"))
    }

    @Test
    fun `invalid remote version is not offered`() {
        assertFalse(VersionUtils.isRemoteNewer("latest", "1.0.0"))
    }

    @Test
    fun `same base version matches stable and prerelease forms`() {
        assertTrue(VersionUtils.isSameBaseVersion("0.5.6-beta", "0.5.6"))
        assertTrue(VersionUtils.isSameBaseVersion("v0.5.6-rc.1", "0.5.6+138"))
        assertFalse(VersionUtils.isSameBaseVersion("0.5.7-beta", "0.5.6"))
        assertFalse(VersionUtils.isSameBaseVersion("not-a-version", "0.5.6"))
    }

    @Test
    fun `current beta naming is recognized as prerelease`() {
        assertTrue(VersionUtils.isPrerelease("0.8.12-beta"))
    }
}
