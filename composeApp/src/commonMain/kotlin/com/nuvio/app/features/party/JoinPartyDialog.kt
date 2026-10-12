package com.nuvio.app.features.party

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.party_code_label
import nuvio.composeapp.generated.resources.party_join_action
import nuvio.composeapp.generated.resources.party_join_description
import nuvio.composeapp.generated.resources.party_join_title
import nuvio.composeapp.generated.resources.party_leave
import nuvio.composeapp.generated.resources.party_now_watching
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_watch_with_party
import org.jetbrains.compose.resources.stringResource

object PartyPrompt {
    private val _joinCode = MutableStateFlow<String?>(null)
    val joinCode: StateFlow<String?> = _joinCode.asStateFlow()

    private val _openMedia = MutableSharedFlow<PartyMedia>(extraBufferCapacity = 1)
    val openMedia: SharedFlow<PartyMedia> = _openMedia.asSharedFlow()

    fun showJoin(code: String = "") {
        _joinCode.value = code
    }

    fun dismissJoin() {
        _joinCode.value = null
    }

    fun openPartyMedia(media: PartyMedia) {
        _openMedia.tryEmit(media)
    }
}

@Composable
fun JoinPartyDialog(
    initialCode: String,
    onDismiss: () -> Unit,
) {
    val currentParty = remember {
        PartyRepository.state.value.party?.takeIf { party ->
            initialCode.isBlank() || normalizePartyCode(initialCode) == party.code
        }
    }
    if (currentParty != null) {
        CurrentPartyDialog(currentParty, onDismiss)
    } else {
        JoinCodeDialog(initialCode, onDismiss)
    }
}

@Composable
private fun CurrentPartyDialog(
    party: ActiveParty,
    onDismiss: () -> Unit,
) {
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.party?.id) {
        if (state.party?.id != party.id) onDismiss()
    }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.party_title),
        message = stringResource(Res.string.party_now_watching, party.media.displayTitle()),
    ) {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.party_leave),
                onClick = {
                    PartyRepository.leave()
                    onDismiss()
                },
            )
            DialogButton(
                text = stringResource(Res.string.party_watch_with_party),
                onClick = {
                    PartyPrompt.openPartyMedia(party.media)
                    onDismiss()
                },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun JoinCodeDialog(
    initialCode: String,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH)) }
    var error by remember {
        mutableStateOf(
            when {
                !PartyRepository.isSupported -> PartyError.Unavailable
                !PartyRepository.canUse -> PartyError.SignInRequired
                else -> null
            },
        )
    }

    fun submit() {
        if (!isValidPartyCode(code) || state.busy) return
        scope.launch {
            error = null
            val result = PartyRepository.join(code)
            if (result == null) {
                PartyRepository.state.value.party?.media?.let(PartyPrompt::openPartyMedia)
                onDismiss()
            } else {
                error = result
            }
        }
    }

    LaunchedEffect(Unit) {
        if (error == null && isValidPartyCode(initialCode)) submit()
    }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.party_join_title),
        message = stringResource(Res.string.party_join_description),
    ) {
        NuvioInputField(
            value = code,
            onValueChange = { value ->
                code = normalizePartyCode(value).take(PARTY_CODE_LENGTH)
                if (error != PartyError.SignInRequired && error != PartyError.Unavailable) error = null
            },
            placeholder = stringResource(Res.string.party_code_label),
        )
        error?.let { current ->
            Text(
                text = stringResource(current.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.party_join_action),
                onClick = ::submit,
                style = DialogButtonStyle.Primary,
                enabled = isValidPartyCode(code) && PartyRepository.canUse,
                loading = state.busy,
            )
        }
    }
}
