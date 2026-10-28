package com.nuvio.app.features.updater

/**
 * Release list used by both What's New and the in-app updater.
 *
 * There is no channel filter here anymore: stable and beta releases are
 * all eligible, sorted by version descending, so the top release on
 * GitHub is always the one shown/offered, whichever kind it is. Drafts
 * and tags that do not parse as a version are still excluded. The
 * [channel] parameter is kept only so existing callers do not change;
 * it no longer filters anything.
 */
internal object ReleaseSelector {
    fun eligibleReleases(
        releases: List<GitHubReleaseDto>,
        channel: UpdateChannel
    ): List<GitHubReleaseDto> = releases
        .asSequence()
        .filterNot(GitHubReleaseDto::draft)
        .mapNotNull { release ->
            val version = releaseVersion(release) ?: return@mapNotNull null
            ReleaseCandidate(
                release = release,
                version = version
            )
        }
        .sortedByDescending(ReleaseCandidate::version)
        .map(ReleaseCandidate::release)
        .toList()

    private fun releaseVersion(release: GitHubReleaseDto): SemanticVersion? =
        VersionUtils.parse(release.tagName) ?: VersionUtils.parse(release.name)

    private data class ReleaseCandidate(
        val release: GitHubReleaseDto,
        val version: SemanticVersion
    )
}
