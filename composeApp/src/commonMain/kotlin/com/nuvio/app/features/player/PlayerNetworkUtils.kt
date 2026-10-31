package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkStatusRepository

/**
 * Synchronous offline check for player runtime code, backed by the existing
 * [NetworkStatusRepository] state. It reads the current probe result only and
 * changes no probe behavior.
 */
internal fun isNetworkOffline(): Boolean =
    NetworkStatusRepository.uiState.value.isOfflineLike
