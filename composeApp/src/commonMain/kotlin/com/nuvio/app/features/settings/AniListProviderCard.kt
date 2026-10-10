package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.features.anilist.AniListAuthError
import com.nuvio.app.features.anilist.AniListConfig
import com.nuvio.app.features.anilist.AniListSyncError
import com.nuvio.app.features.anilist.AniListTracker
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AniListProviderCard(modifier: Modifier = Modifier) {
    val auth by remember { AniListTracker.ensureLoaded(); AniListTracker.authState }.collectAsStateWithLifecycle()
    val sync by AniListTracker.sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val syncStatus = sync.takeIf { it.scope == auth.scope }
    TrackingProviderCard(
        brand = TrackingBrand.ANILIST,
        mode = when {
            auth.isAuthenticated -> TrackingConnectionCardMode.CONNECTED
            auth.awaitingApproval -> TrackingConnectionCardMode.AWAITING_APPROVAL
            else -> TrackingConnectionCardMode.DISCONNECTED
        },
        credentialsConfigured = AniListTracker.hasRequiredCredentials(),
        isLoading = auth.isBusy,
        connectedLabel = stringResource(
            Res.string.settings_anilist_connected_as,
            auth.viewer?.name ?: stringResource(Res.string.settings_anilist_account_fallback),
        ),
        connectedDescription = stringResource(Res.string.settings_anilist_connected_description),
        signInDescription = stringResource(Res.string.settings_anilist_sign_in_description),
        finishSignInLabel = stringResource(Res.string.settings_anilist_finish_sign_in),
        approvalDescription = stringResource(Res.string.settings_anilist_approval_description),
        connectLabel = stringResource(Res.string.settings_anilist_connect),
        openLoginLabel = stringResource(Res.string.settings_anilist_open_login),
        disconnectLabel = stringResource(Res.string.settings_anilist_disconnect),
        missingCredentialsMessage = stringResource(Res.string.settings_anilist_missing_credentials),
        syncLabel = stringResource(Res.string.settings_anilist_sync_now),
        isSyncing = syncStatus?.isLoading == true,
        statusMessage = when {
            syncStatus?.isLoading == true -> stringResource(Res.string.settings_anilist_syncing)
            auth.isAuthenticated && syncStatus?.snapshot?.checkedAtEpochMs != null -> stringResource(
                Res.string.settings_anilist_last_synced,
                formatLocalDateTime(syncStatus.snapshot.checkedAtEpochMs),
            )
            else -> null
        },
        errorMessage = aniListAccountError(auth.error, syncStatus?.error),
        websiteLabel = stringResource(Res.string.settings_anilist_visit),
        websiteUrl = AniListConfig.WEBSITE_URL,
        onConnectRequested = { AniListTracker.connect() },
        onResumeAuthorization = { AniListTracker.pendingAuthorizationUrl() },
        onCancelAuthorization = { AniListTracker.cancelAuthorization() },
        onDisconnect = { AniListTracker.disconnect() },
        onSyncRequested = {
            scope.launch {
                WatchProgressSourceCoordinator.refreshProviderAndActiveSource(
                    profileId = auth.scope.profileId,
                    providerId = TrackingProviderId.ANILIST,
                    refreshProvider = {
                        AniListTracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                        AniListTracker.sync.state.value.let { it.scope == auth.scope && it.hasLoaded && it.error == null }
                    },
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun aniListAccountError(auth: AniListAuthError?, sync: AniListSyncError?): String? = when {
    auth == AniListAuthError.ACCESS_DENIED -> stringResource(Res.string.settings_anilist_auth_denied)
    auth == AniListAuthError.INVALID_CALLBACK -> stringResource(Res.string.settings_anilist_auth_invalid)
    auth == AniListAuthError.AUTHORIZATION_REVOKED || auth == AniListAuthError.AUTHORIZATION_EXPIRED ->
        stringResource(Res.string.settings_anilist_auth_revoked)
    sync == AniListSyncError.RATE_LIMIT -> stringResource(Res.string.settings_anilist_rate_limited)
    sync == AniListSyncError.AUTHORIZATION_REVOKED -> stringResource(Res.string.settings_anilist_auth_revoked)
    sync == AniListSyncError.UNAVAILABLE -> stringResource(Res.string.settings_anilist_unavailable)
    else -> null
}
