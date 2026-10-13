package com.nuvio.app.features.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_host_hint
import nuvio.composeapp.generated.resources.party_hosted_by
import nuvio.composeapp.generated.resources.party_join_action
import nuvio.composeapp.generated.resources.party_join_description
import nuvio.composeapp.generated.resources.party_join_title
import nuvio.composeapp.generated.resources.party_joined_title
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_watch_with_party
import org.jetbrains.compose.resources.stringResource

private enum class PartyScreenMode {
    Join,
    Joined,
    Party,
}

@Composable
fun PartyScreen(
    initialCode: String,
    onBack: () -> Unit,
    onWatch: (PartyMedia) -> Unit,
) {
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val party = state.party
    var joined by remember { mutableStateOf<ActiveParty?>(null) }
    var pendingCode by remember {
        mutableStateOf(initialCode.takeIf { isValidPartyCode(it) && normalizePartyCode(it) != party?.code }.orEmpty())
    }
    var joining by remember { mutableStateOf(party == null || pendingCode.isNotEmpty()) }
    val mode = when {
        joined != null -> PartyScreenMode.Joined
        party != null && !joining -> PartyScreenMode.Party
        else -> PartyScreenMode.Join
    }
    LaunchedEffect(joined) {
        val current = joined ?: return@LaunchedEffect
        delay(1600)
        onWatch(current.media)
        joined = null
    }

    NuvioScreen(modifier = Modifier.fillMaxSize()) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.party_title),
                onBack = onBack,
            )
        }
        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(140))
                    },
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(),
                ) { target ->
                    when (target) {
                        PartyScreenMode.Join -> JoinContent(
                            initialCode = pendingCode,
                            onJoined = { newParty ->
                                pendingCode = ""
                                joining = false
                                joined = newParty
                            },
                        )
                        PartyScreenMode.Joined -> (joined ?: party)?.let { JoinedContent(it) }
                        PartyScreenMode.Party -> party?.let { PartyContent(it, onWatch) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinContent(
    initialCode: String,
    onJoined: (ActiveParty) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val available by PartyRepository.available.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val autoJoin = remember { isValidPartyCode(initialCode) }
    var code by remember {
        mutableStateOf(if (autoJoin) "" else normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH))
    }
    var error by remember { mutableStateOf<PartyError?>(null) }
    val blockedError = when {
        !available -> PartyError.Unavailable
        !PartyRepository.canUse -> PartyError.SignInRequired
        else -> null
    }
    val shownError = blockedError ?: error

    fun submit() {
        if (!isValidPartyCode(code) || state.busy || blockedError != null) return
        scope.launch {
            error = null
            val result = PartyRepository.join(code)
            val party = PartyRepository.state.value.party
            if (result == null && party != null) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onJoined(party)
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                error = result ?: PartyError.Network
                shake.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 420
                        -14f at 60
                        12f at 120
                        -8f at 180
                        6f at 240
                        -3f at 300
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (blockedError != null) return@LaunchedEffect
        if (autoJoin) {
            val target = normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH)
            delay(250)
            target.indices.forEach { index ->
                delay(60)
                code = target.take(index + 1)
            }
            submit()
        } else {
            delay(650)
            runCatching { focusRequester.requestFocus() }
            delay(550)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SeatsHero(
            size = 72.dp,
            ringColor = tokens.colors.background,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Text(
            text = stringResource(Res.string.party_join_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.party_join_description),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        CodeField(
            code = code,
            onCodeChange = { value ->
                if (state.busy) return@CodeField
                if (value.length > code.length) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                code = value
                error = null
                if (isValidPartyCode(value)) submit()
            },
            onDone = ::submit,
            focusRequester = focusRequester,
            enabled = blockedError == null,
            modifier = Modifier
                .padding(top = 8.dp)
                .graphicsLayer { translationX = shake.value.dp.toPx() },
        )
        AnimatedContent(
            targetState = shownError,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 }) togetherWith fadeOut(tween(120))
            },
        ) { current ->
            if (current != null) {
                Text(
                    text = stringResource(current.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                    textAlign = TextAlign.Center,
                )
            }
        }
        DialogButton(
            text = stringResource(Res.string.party_join_action),
            onClick = ::submit,
            style = DialogButtonStyle.Primary,
            enabled = isValidPartyCode(code) && blockedError == null,
            loading = state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
        Text(
            text = stringResource(Res.string.party_host_hint),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun JoinedContent(party: ActiveParty) {
    val tokens = MaterialTheme.nuvio
    val host = party.member(party.hostMemberId)?.displayName
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarStack(
            members = party.members,
            size = 80.dp,
            animateIn = true,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(Res.string.party_joined_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        if (host != null) {
            Text(
                text = stringResource(Res.string.party_hosted_by, host),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textSecondary,
            )
        }
        Text(
            text = party.media.displayTitle(),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PartyContent(
    party: ActiveParty,
    onWatch: (PartyMedia) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val image = party.media.background ?: party.media.poster
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
            Text(
                text = party.media.displayTitle(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
        }
        PartyOverview(
            party = party,
            ringColor = tokens.colors.background,
            onRemove = { member -> PartyRepository.removeMember(member.id) },
        )
        if (party.isHost) {
            HostOnlySwitch(
                checked = party.hostOnly,
                onCheckedChange = PartyRepository::setHostOnly,
            )
        } else if (party.hostOnly) {
            HostOnlyNote()
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogButton(
                text = stringResource(Res.string.party_watch_with_party),
                onClick = { onWatch(party.media) },
                style = DialogButtonStyle.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            )
            PartyActions(
                party = party,
                onLeave = PartyRepository::leave,
                onEnd = PartyRepository::end,
                buttonModifier = Modifier.height(52.dp),
            )
        }
    }
}
