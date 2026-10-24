package com.nuvio.app.core.startup

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private const val LogTag = "App/StartupPreload"
private const val SlowPreloadWarningMs = 6_000L

object StartupPreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Deferred<Unit>? = null

    val isComplete: Boolean
        get() = job?.isCompleted == true

    suspend fun awaitReady() {
        val active = job ?: start()
        if (withTimeoutOrNull(SlowPreloadWarningMs) { active.await() } == null) {
            InAppLogger.warn(LogTag, "preload slower than ${SlowPreloadWarningMs}ms, still waiting")
            active.await()
        }
    }

    private fun start(): Deferred<Unit> = scope.async {
        val started = TimeSource.Monotonic.markNow()
        step("profiles") { ProfileRepository.loadCachedProfiles() }
        withContext(Dispatchers.IO) {
            step("library") { LibraryRepository.ensureLoaded() }
            step("watched") { WatchedRepository.ensureLoaded() }
            step("watchProgress") { WatchProgressRepository.ensureLoaded() }
            step("continueWatching") { ContinueWatchingPreferencesRepository.ensureLoaded() }
            step("playerSettings") { PlayerSettingsRepository.ensureLoaded() }
            step("p2pSettings") { P2pSettingsRepository.ensureLoaded() }
            step("servers") { ServerRepository.ensureLoaded() }
            step("metaScreen") { MetaScreenSettingsRepository.ensureLoaded() }
            step("releaseNotifications") { EpisodeReleaseNotificationsRepository.ensureLoaded() }
            step("liveTv") { LiveTvRepository.ensureLoaded() }
            step("homeSnapshot") { HomeRepository.preloadSnapshot() }
        }
        InAppLogger.info(LogTag, "preloaded in ${started.elapsedNow().inWholeMilliseconds}ms")
    }.also { job = it }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            InAppLogger.warn(LogTag, "$name failed: ${error::class.simpleName}: ${error.message}")
        }
    }
}
