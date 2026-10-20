package com.nuvio.app.features.discord

import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

internal data class DiscordRpcTimestamps(
    val start: Long? = null,
    val end: Long? = null,
)

internal data class DiscordRpcButton(
    val label: String,
    val url: String,
)

internal data class CommonDiscordRpc(
    val name: String = "",
    val type: Int = DiscordRpcActivityType.Watching.value,
    val details: String? = null,
    val state: String? = null,
    val largeImage: DiscordRpcImage? = null,
    val smallImage: DiscordRpcImage? = null,
    val largeText: String? = null,
    val smallText: String? = null,
    val time: DiscordRpcTimestamps? = null,
    val buttons: List<DiscordRpcButton> = emptyList(),
)

internal class DiscordRpc(
    val token: String,
) {
    private val discordWebSocket = DiscordGatewayConnection(token)

    val state: StateFlow<DiscordGatewayState>
        get() = discordWebSocket.state

    val user: StateFlow<DiscordReadyUser?>
        get() = discordWebSocket.user

    val lastError: StateFlow<String?>
        get() = discordWebSocket.lastError

    fun closeRPC() {
        discordWebSocket.close()
    }

    fun isRpcRunning(): Boolean = discordWebSocket.state.value == DiscordGatewayState.Ready

    suspend fun updateRPC(
        commonRpc: CommonDiscordRpc,
        status: String,
        applicationId: String,
    ) {
        if (token.isBlank()) return
        val largeImage = commonRpc.largeImage?.let { image ->
            withTimeoutOrNull(IMAGE_RESOLVE_TIMEOUT_MS) { image.resolveImage(token, applicationId) }
        }?.validAssetKey()
        val smallImage = commonRpc.smallImage?.let { image ->
            withTimeoutOrNull(IMAGE_RESOLVE_TIMEOUT_MS) { image.resolveImage(token, applicationId) }
        }?.validAssetKey()
        val buttons = commonRpc.buttons
            .filter { it.label.isNotBlank() && it.url.isNotBlank() }
            .take(2)
        val presence = DiscordPresence(
            activities = listOf(
                DiscordActivity(
                    name = commonRpc.name.sanitize(),
                    details = commonRpc.details?.takeIf { it.isNotEmpty() }?.sanitize(),
                    state = commonRpc.state?.takeIf { it.isNotEmpty() }?.sanitize(),
                    type = commonRpc.type,
                    timestamps = commonRpc.time
                        ?.takeIf { it.start != null || it.end != null }
                        ?.let { DiscordActivityTimestamps(start = it.start, end = it.end) },
                    assets = DiscordActivityAssets(
                        largeImage = largeImage,
                        smallImage = smallImage,
                        largeText = commonRpc.largeText?.takeIf { it.isNotEmpty() }?.sanitize(),
                        smallText = commonRpc.smallText?.takeIf { it.isNotEmpty() }?.sanitize(),
                    ).takeIf { largeImage != null || smallImage != null },
                    buttons = buttons
                        .map { it.label.take(DiscordRpcConfig.MAX_BUTTON_LABEL_LENGTH) }
                        .takeIf { it.isNotEmpty() },
                    metadata = DiscordActivityMetadata(buttonUrls = buttons.map { it.url })
                        .takeIf { buttons.isNotEmpty() },
                    applicationId = applicationId.takeIf { it.isNotEmpty() } ?: DiscordRpcConfig.APPLICATION_ID,
                ),
            ),
            afk = true,
            since = commonRpc.time?.start ?: Clock.System.now().toEpochMilliseconds(),
            status = status,
        )
        discordWebSocket.connect()
        discordWebSocket.updatePresence(presence)
    }

    private companion object {
        const val IMAGE_RESOLVE_TIMEOUT_MS = 8_000L
    }

    private fun String.validAssetKey(): String? {
        if (length <= DiscordRpcConfig.MAX_ASSET_KEY_LENGTH) return this
        InAppLogger.warn("DiscordRPC", "Dropping image asset, key length $length exceeds ${DiscordRpcConfig.MAX_ASSET_KEY_LENGTH}")
        return null
    }

    private fun String.sanitize(): String =
        if (length > DiscordRpcConfig.MAX_TEXT_LENGTH) substring(0, DiscordRpcConfig.MAX_TEXT_LENGTH) else this
}
