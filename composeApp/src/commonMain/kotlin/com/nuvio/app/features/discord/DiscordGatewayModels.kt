package com.nuvio.app.features.discord

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = DiscordOpCodeSerializer::class)
internal enum class DiscordOpCode(val value: Int) {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    PRESENCE_UPDATE(3),
    VOICE_STATE(4),
    RESUME(6),
    RECONNECT(7),
    REQUEST_GUILD_MEMBERS(8),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    UNKNOWN(-1),
}

internal object DiscordOpCodeSerializer : KSerializer<DiscordOpCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DiscordOpCode", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): DiscordOpCode {
        val opCode = decoder.decodeInt()
        return DiscordOpCode.entries.firstOrNull { it.value == opCode } ?: DiscordOpCode.UNKNOWN
    }

    override fun serialize(encoder: Encoder, value: DiscordOpCode) {
        encoder.encodeInt(value.value)
    }
}

@Serializable
internal data class DiscordPayload(
    @SerialName("t")
    val t: String? = null,
    @SerialName("s")
    val s: Int? = null,
    @SerialName("op")
    val op: DiscordOpCode? = null,
)

@Serializable
internal data class DiscordPayloadData<T>(
    @SerialName("d")
    val d: T? = null,
)

@Serializable
internal data class DiscordOutgoingPayload<T>(
    @SerialName("op")
    val op: DiscordOpCode? = null,
    @SerialName("d")
    val d: T? = null,
)

@Serializable
internal data class DiscordHeartbeat(
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long,
)

@Serializable
internal data class DiscordIdentify(
    @SerialName("capabilities")
    val capabilities: Int,
    @SerialName("compress")
    val compress: Boolean,
    @SerialName("large_threshold")
    val largeThreshold: Int,
    @SerialName("properties")
    val properties: DiscordIdentifyProperties,
    @SerialName("token")
    val token: String,
) {
    companion object {
        fun String.toIdentifyPayload() = DiscordIdentify(
            capabilities = 65,
            compress = false,
            largeThreshold = 100,
            properties = DiscordIdentifyProperties(
                browser = "Discord Client",
                device = "ktor",
                os = "Windows",
            ),
            token = this,
        )
    }
}

@Serializable
internal data class DiscordIdentifyProperties(
    @SerialName("browser")
    val browser: String,
    @SerialName("device")
    val device: String,
    @SerialName("os")
    val os: String,
)

@Serializable
internal data class DiscordResume(
    @SerialName("seq")
    val seq: Int,
    @SerialName("session_id")
    val sessionId: String?,
    @SerialName("token")
    val token: String,
)

@Serializable
internal data class DiscordReady(
    @SerialName("resume_gateway_url")
    val resumeGatewayUrl: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("user")
    val user: DiscordReadyUser? = null,
)

@Serializable
internal data class DiscordReadyUser(
    @SerialName("id")
    val id: String? = null,
    @SerialName("username")
    val username: String? = null,
    @SerialName("global_name")
    val globalName: String? = null,
    @SerialName("avatar")
    val avatar: String? = null,
    @SerialName("bio")
    val bio: String? = null,
    @SerialName("premium_type")
    val premiumType: Int? = null,
)

@Serializable
internal data class DiscordPresence(
    @SerialName("activities")
    val activities: List<DiscordActivity?>?,
    @SerialName("afk")
    val afk: Boolean? = true,
    @SerialName("since")
    val since: Long? = 0L,
    @SerialName("status")
    val status: String? = "online",
)

@Serializable
internal data class DiscordActivity(
    @SerialName("name")
    val name: String?,
    @SerialName("state")
    val state: String? = null,
    @SerialName("details")
    val details: String? = null,
    @SerialName("type")
    val type: Int? = 0,
    @SerialName("platform")
    val platform: String? = null,
    @SerialName("timestamps")
    val timestamps: DiscordActivityTimestamps? = null,
    @SerialName("assets")
    val assets: DiscordActivityAssets? = null,
    @SerialName("buttons")
    val buttons: List<String?>? = null,
    @SerialName("metadata")
    val metadata: DiscordActivityMetadata? = null,
    @SerialName("application_id")
    val applicationId: String? = null,
    @SerialName("url")
    val url: String? = null,
)

@Serializable
internal data class DiscordActivityAssets(
    @SerialName("large_image")
    val largeImage: String?,
    @SerialName("small_image")
    val smallImage: String?,
    @SerialName("large_text")
    val largeText: String? = null,
    @SerialName("small_text")
    val smallText: String? = null,
)

@Serializable
internal data class DiscordActivityMetadata(
    @SerialName("button_urls")
    val buttonUrls: List<String?>?,
)

@Serializable
internal data class DiscordActivityTimestamps(
    @SerialName("end")
    val end: Long? = null,
    @SerialName("start")
    val start: Long? = null,
)
