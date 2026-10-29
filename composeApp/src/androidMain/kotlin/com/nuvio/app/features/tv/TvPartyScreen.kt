package com.nuvio.app.features.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.party.PartyError
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyRepository
import kotlinx.coroutines.launch

/**
 * TV Watch Party: join by code, or create a party for the media that is
 * currently playing (the player passes it in). The host's stream is shared
 * automatically once playback starts; members play the shared stream from
 * here, and play/pause/seek then stay in sync through the player.
 * A party needs the same server setup as the phone — without it this
 * screen says so instead of pretending to work.
 */
@Composable
fun TvPartyScreen(
    media: PartyMedia?,
    onBack: () -> Unit,
    onPlay: (TvPlayRequest) -> Unit,
) {
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val sharedStream by PartyRepository.sharedStream.collectAsStateWithLifecycle()
    val partyAvailable by PartyRepository.available.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var joinMessage by remember { mutableStateOf<String?>(null) }
    var createMessage by remember { mutableStateOf<String?>(null) }
    val party = partyState.party

    BackHandler { onBack() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        item {
            Text(
                text = "Watch Party",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
            )
        }
        if (!partyAvailable) {
            item {
                Text(
                    text = "Watch Party ke liye server setup chahiye — phone par server connect karo, phir dobara aao.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }
        } else if (party != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 48.dp)) {
                    Text(
                        text = "Party code: ${party.code}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = party.media.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(8.dp))
                    val memberNames = party.members.joinToString(", ") { it.displayName }
                    Text(
                        text = if (memberNames.isNotBlank()) {
                            "Members (${party.members.size}): $memberNames"
                        } else {
                            "Members: sirf tum"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    if (!party.connected) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Connecting…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    if (!party.isHost && sharedStream != null) {
                        val stream = sharedStream
                        if (stream != null) {
                            TvControlButton(
                                label = "Play shared stream",
                                onClick = {
                                    onPlay(
                                        TvPlayRequest(
                                            title = stream.name ?: party.media.title,
                                            sourceUrl = stream.url,
                                            sourceHeaders = stream.headers,
                                        ),
                                    )
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    if (party.isHost) {
                        Text(
                            text = "Tum host ho — Back dabakar player par jao, tumhara stream members ko auto-share ho jayega.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(12.dp))
                        TvControlButton(
                            label = "End party",
                            onClick = { PartyRepository.end() },
                        )
                    } else {
                        TvControlButton(
                            label = "Leave party",
                            onClick = { PartyRepository.leave() },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TvControlButton(label = "Back", onClick = onBack)
                }
            }
        } else {
            item {
                Column(modifier = Modifier.padding(horizontal = 48.dp)) {
                    Text(
                        text = "Join party",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Party code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    TvControlButton(
                        label = if (busy) "Joining…" else "Join",
                        onClick = {
                            val trimmed = code.trim()
                            if (trimmed.isEmpty() || busy) return@TvControlButton
                            busy = true
                            joinMessage = null
                            scope.launch {
                                val error = PartyRepository.join(trimmed)
                                busy = false
                                joinMessage = error?.let { partyErrorText(it) }
                            }
                        },
                    )
                    if (joinMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = joinMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "Create party",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (media != null) {
                        Text(
                            text = "Current media: ${media.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(12.dp))
                        TvControlButton(
                            label = if (busy) "Creating…" else "Create party",
                            onClick = {
                                if (busy) return@TvControlButton
                                busy = true
                                createMessage = null
                                scope.launch {
                                    val error = PartyRepository.create(media)
                                    if (error == null) {
                                        PartyRepository.setSharing(true)
                                        createMessage = "Party ban gayi — Back dabakar player par jao."
                                    } else {
                                        createMessage = partyErrorText(error)
                                    }
                                    busy = false
                                }
                            },
                        )
                    } else {
                        Text(
                            text = "Party banane ke liye pehle Details se koi video play karo, phir player me Party dabao.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    if (createMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = createMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TvControlButton(label = "Back", onClick = onBack)
                }
            }
        }
    }
}

private fun partyErrorText(error: PartyError): String = when (error) {
    PartyError.NotFound -> "Party nahi mili — code dobara check karo."
    PartyError.Full -> "Party full hai."
    PartyError.Removed -> "Party se nikaal diya gaya."
    PartyError.Ended -> "Party khatam ho gayi hai."
    PartyError.RateLimited -> "Thodi der baad dobara try karo."
    PartyError.SignInRequired -> "Watch Party ke liye sign-in chahiye."
    PartyError.Unavailable -> "Watch Party abhi available nahi hai."
    PartyError.Network -> "Network error — dobara try karo."
}
