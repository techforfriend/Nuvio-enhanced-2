package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.publicmetadb.PublicMetaDbAuthError
import com.nuvio.app.features.publicmetadb.PublicMetaDbConfig
import com.nuvio.app.features.publicmetadb.PublicMetaDbSyncError
import com.nuvio.app.features.publicmetadb.PublicMetaDbTracker
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PublicMetaDbProviderCard(modifier: Modifier = Modifier) {
    val auth by remember { PublicMetaDbTracker.ensureLoaded(); PublicMetaDbTracker.authState }.collectAsStateWithLifecycle()
    val sync by PublicMetaDbTracker.sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    val syncStatus = sync.takeIf { it.scope == auth.scope }
    TrackingProviderCard(
        brand = TrackingBrand.PUBLICMETADB,
        mode = if (auth.isAuthenticated) TrackingConnectionCardMode.CONNECTED else TrackingConnectionCardMode.DISCONNECTED,
        credentialsConfigured = true,
        isLoading = auth.isBusy,
        connectedLabel = stringResource(
            Res.string.settings_publicmetadb_connected_as,
            auth.keyHint ?: stringResource(Res.string.settings_publicmetadb_account_fallback),
        ),
        connectedDescription = stringResource(Res.string.settings_publicmetadb_connected_description),
        signInDescription = stringResource(Res.string.settings_publicmetadb_sign_in_description),
        finishSignInLabel = stringResource(Res.string.settings_publicmetadb_finish_sign_in),
        approvalDescription = stringResource(Res.string.settings_publicmetadb_approval_description),
        connectLabel = stringResource(Res.string.settings_publicmetadb_connect),
        openLoginLabel = stringResource(Res.string.settings_publicmetadb_open_login),
        disconnectLabel = stringResource(Res.string.settings_publicmetadb_disconnect),
        missingCredentialsMessage = stringResource(Res.string.settings_publicmetadb_missing_credentials),
        syncLabel = stringResource(Res.string.settings_publicmetadb_sync_now),
        isSyncing = syncStatus?.isLoading == true,
        statusMessage = when {
            syncStatus?.isLoading == true -> stringResource(Res.string.settings_publicmetadb_syncing)
            auth.isAuthenticated && syncStatus?.snapshot?.checkedAtEpochMs != null -> stringResource(
                Res.string.settings_publicmetadb_last_synced,
                formatLocalDateTime(syncStatus.snapshot.checkedAtEpochMs),
            )
            else -> null
        },
        errorMessage = publicMetaDbAccountError(auth.error.takeUnless { showKeyDialog }, syncStatus?.error),
        websiteLabel = stringResource(Res.string.settings_publicmetadb_visit),
        websiteUrl = PublicMetaDbConfig.WEBSITE_URL,
        onConnectRequested = {
            PublicMetaDbTracker.clearError()
            showKeyDialog = true
            null
        },
        onResumeAuthorization = {
            showKeyDialog = true
            null
        },
        onCancelAuthorization = { showKeyDialog = false },
        onDisconnect = { PublicMetaDbTracker.disconnect() },
        onSyncRequested = {
            scope.launch {
                WatchProgressSourceCoordinator.refreshProviderAndActiveSource(
                    profileId = auth.scope.profileId,
                    providerId = TrackingProviderId.PUBLICMETADB,
                    refreshProvider = {
                        PublicMetaDbTracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                        PublicMetaDbTracker.sync.state.value.let { it.scope == auth.scope && it.hasLoaded && it.error == null }
                    },
                )
            }
        },
        modifier = modifier,
    )

    if (showKeyDialog && !auth.isAuthenticated) {
        PublicMetaDbApiKeyDialog(
            isBusy = auth.isBusy,
            errorMessage = publicMetaDbAccountError(auth.error, null),
            onConnect = { key ->
                scope.launch {
                    if (PublicMetaDbTracker.connect(key)) showKeyDialog = false
                }
            },
            onDismiss = {
                showKeyDialog = false
                PublicMetaDbTracker.clearError()
            },
        )
    }
}

@Composable
private fun PublicMetaDbApiKeyDialog(
    isBusy: Boolean,
    errorMessage: String?,
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    var apiKey by rememberSaveable { mutableStateOf("") }
    val trimmed = apiKey.trim()
    DialogSurface(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = stringResource(Res.string.settings_publicmetadb_api_key_title),
        message = stringResource(Res.string.settings_publicmetadb_api_key_description),
    ) {
        SettingsSecretTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = stringResource(Res.string.settings_publicmetadb_api_key_label),
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null,
            enabled = !isBusy,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { if (trimmed.isNotEmpty() && !isBusy) onConnect(trimmed) }),
        )
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }
        TextButton(onClick = { runCatching { uriHandler.openUri(PublicMetaDbConfig.API_KEY_URL) } }) {
            Text(stringResource(Res.string.settings_publicmetadb_get_api_key))
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                enabled = !isBusy,
            )
            DialogButton(
                text = stringResource(Res.string.settings_publicmetadb_connect_action),
                onClick = { onConnect(trimmed) },
                style = DialogButtonStyle.Primary,
                enabled = trimmed.isNotEmpty(),
                loading = isBusy,
            )
        }
    }
}

@Composable
private fun publicMetaDbAccountError(auth: PublicMetaDbAuthError?, sync: PublicMetaDbSyncError?): String? = when {
    auth == PublicMetaDbAuthError.MISSING_KEY -> stringResource(Res.string.settings_publicmetadb_missing_key)
    auth == PublicMetaDbAuthError.INVALID_KEY -> stringResource(Res.string.settings_publicmetadb_invalid_key)
    auth == PublicMetaDbAuthError.REQUEST_FAILED -> stringResource(Res.string.settings_publicmetadb_request_failed)
    auth == PublicMetaDbAuthError.RATE_LIMITED -> stringResource(Res.string.settings_publicmetadb_rate_limited)
    auth == PublicMetaDbAuthError.BLOCKED -> stringResource(Res.string.settings_publicmetadb_blocked)
    auth == PublicMetaDbAuthError.AUTHORIZATION_REVOKED -> stringResource(Res.string.settings_publicmetadb_auth_revoked)
    sync == PublicMetaDbSyncError.RATE_LIMIT -> stringResource(Res.string.settings_publicmetadb_rate_limited)
    sync == PublicMetaDbSyncError.AUTHORIZATION_REVOKED -> stringResource(Res.string.settings_publicmetadb_auth_revoked)
    sync == PublicMetaDbSyncError.BLOCKED -> stringResource(Res.string.settings_publicmetadb_blocked)
    sync == PublicMetaDbSyncError.UNAVAILABLE -> stringResource(Res.string.settings_publicmetadb_unavailable)
    else -> null
}
