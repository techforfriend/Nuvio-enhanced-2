package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuvio.app.features.wetrakr.WeTrakrAuthError
import com.nuvio.app.features.wetrakr.WeTrakrConfig
import com.nuvio.app.features.wetrakr.WeTrakrSyncError
import com.nuvio.app.features.wetrakr.WeTrakrTracker
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WeTrakrProviderCard(modifier: Modifier = Modifier) {
    val auth by remember { WeTrakrTracker.ensureLoaded(); WeTrakrTracker.authState }.collectAsStateWithLifecycle()
    val sync by WeTrakrTracker.sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var showCodeSheet by remember { mutableStateOf(false) }
    val syncStatus = sync.takeIf { it.scope == auth.scope }
    TrackingProviderCard(
        brand = TrackingBrand.WETRAKR,
        mode = when {
            auth.isAuthenticated -> TrackingConnectionCardMode.CONNECTED
            auth.awaitingApproval -> TrackingConnectionCardMode.AWAITING_APPROVAL
            else -> TrackingConnectionCardMode.DISCONNECTED
        },
        credentialsConfigured = WeTrakrTracker.hasRequiredCredentials(),
        isLoading = auth.isBusy,
        connectedLabel = stringResource(
            Res.string.settings_wetrakr_connected_as,
            auth.user?.displayName ?: auth.user?.username ?: stringResource(Res.string.settings_wetrakr_account_fallback),
        ),
        connectedDescription = stringResource(Res.string.settings_wetrakr_connected_description),
        signInDescription = stringResource(Res.string.settings_wetrakr_sign_in_description),
        finishSignInLabel = stringResource(Res.string.settings_wetrakr_finish_sign_in),
        approvalDescription = stringResource(Res.string.settings_wetrakr_approval_description),
        connectLabel = stringResource(Res.string.settings_wetrakr_connect),
        openLoginLabel = stringResource(Res.string.settings_wetrakr_open_login),
        disconnectLabel = stringResource(Res.string.settings_wetrakr_disconnect),
        missingCredentialsMessage = stringResource(Res.string.settings_wetrakr_missing_credentials),
        syncLabel = stringResource(Res.string.settings_wetrakr_sync_now),
        isSyncing = syncStatus?.isLoading == true,
        authorizationCode = auth.userCode.takeIf { auth.awaitingApproval },
        statusMessage = when {
            syncStatus?.isLoading == true -> stringResource(Res.string.settings_wetrakr_syncing)
            auth.isAuthenticated && syncStatus?.snapshot?.checkedAtEpochMs != null -> stringResource(
                Res.string.settings_wetrakr_last_synced,
                formatLocalDateTime(syncStatus.snapshot.checkedAtEpochMs),
            )
            else -> null
        },
        errorMessage = weTrakrAccountError(auth.error, syncStatus?.error),
        websiteLabel = stringResource(Res.string.settings_wetrakr_visit),
        websiteUrl = WeTrakrConfig.WEBSITE_URL,
        onConnectRequested = {
            showCodeSheet = true
            WeTrakrTracker.connect()
            if (!WeTrakrTracker.authState.value.awaitingApproval) showCodeSheet = false
            null
        },
        onResumeAuthorization = { WeTrakrTracker.pendingAuthorizationUrl() },
        onCancelAuthorization = { WeTrakrTracker.cancelAuthorization() },
        onDisconnect = { WeTrakrTracker.disconnect() },
        onSyncRequested = {
            scope.launch {
                WatchProgressSourceCoordinator.refreshProviderAndActiveSource(
                    profileId = auth.scope.profileId,
                    providerId = TrackingProviderId.WETRAKR,
                    refreshProvider = {
                        WeTrakrTracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                        WeTrakrTracker.sync.state.value.let { it.scope == auth.scope && it.hasLoaded && it.error == null }
                    },
                )
            }
        },
        modifier = modifier,
    )

    if (showCodeSheet) {
        TrackingDeviceCodeSheet(
            userCode = auth.userCode,
            verificationUrl = auth.verificationUrl ?: WeTrakrConfig.DEFAULT_VERIFICATION_URL,
            isConnected = auth.isAuthenticated,
            onOpenVerificationPage = { url -> runCatching { uriHandler.openUri(url) } },
            onCancel = { WeTrakrTracker.cancelAuthorization() },
            onDismiss = { showCodeSheet = false },
        )
    }
}

@Composable
private fun weTrakrAccountError(auth: WeTrakrAuthError?, sync: WeTrakrSyncError?): String? = when {
    auth == WeTrakrAuthError.ACCESS_DENIED -> stringResource(Res.string.settings_wetrakr_auth_denied)
    auth == WeTrakrAuthError.CODE_EXPIRED -> stringResource(Res.string.settings_wetrakr_auth_expired)
    auth == WeTrakrAuthError.REQUEST_FAILED -> stringResource(Res.string.settings_wetrakr_auth_failed)
    auth == WeTrakrAuthError.RATE_LIMITED -> stringResource(Res.string.settings_wetrakr_auth_rate_limited)
    auth == WeTrakrAuthError.APP_SUSPENDED -> stringResource(Res.string.settings_wetrakr_app_suspended)
    auth == WeTrakrAuthError.AUTHORIZATION_REVOKED -> stringResource(Res.string.settings_wetrakr_auth_revoked)
    sync == WeTrakrSyncError.RATE_LIMIT -> stringResource(Res.string.settings_wetrakr_rate_limited)
    sync == WeTrakrSyncError.AUTHORIZATION_REVOKED -> stringResource(Res.string.settings_wetrakr_auth_revoked)
    sync == WeTrakrSyncError.PLAN_LIMIT -> stringResource(Res.string.settings_wetrakr_plan_limit)
    sync == WeTrakrSyncError.UNAVAILABLE -> stringResource(Res.string.settings_wetrakr_unavailable)
    else -> null
}
