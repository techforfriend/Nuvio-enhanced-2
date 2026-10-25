package com.nuvio.app.features.player

enum class PlayerToolbarButton(val storageId: String) {
    NEXT_EPISODE("next_episode"),
    SPEED("speed"),
    RESIZE("resize"),
    SUBTITLES("subtitles"),
    CHAPTERS("chapters"),
    AUDIO("audio"),
    QUALITY("quality"),
    SOURCES("sources"),
    EPISODES("episodes"),
    RATE("rate"),
    EXTERNAL_PLAYER("external_player"),
    PLAYBACK_INFO("playback_info"),
    SWITCH_ENGINE("switch_engine"),
    VIDEO_SETTINGS("video_settings"),
    SUBMIT_INTRO("submit_intro"),
    ;

    companion object {
        fun fromStorageId(value: String): PlayerToolbarButton? =
            entries.firstOrNull { it.storageId == value }
    }
}

data class PlayerToolbarEntry(
    val button: PlayerToolbarButton,
    val visible: Boolean,
)

data class PlayerToolbarLayout(val entries: List<PlayerToolbarEntry>) {
    val visibleButtons: List<PlayerToolbarButton>
        get() = entries.filter { it.visible }.map { it.button }

    val hiddenButtons: List<PlayerToolbarButton>
        get() = entries.filterNot { it.visible }.map { it.button }

    fun withVisibility(button: PlayerToolbarButton, visible: Boolean): PlayerToolbarLayout =
        PlayerToolbarLayout(entries.map { if (it.button == button) it.copy(visible = visible) else it })

    fun moved(fromIndex: Int, toIndex: Int): PlayerToolbarLayout {
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) return this
        val updated = entries.toMutableList()
        updated.add(toIndex, updated.removeAt(fromIndex))
        return PlayerToolbarLayout(updated)
    }

    fun encode(): String = entries.joinToString(",") { entry ->
        "${entry.button.storageId}:${if (entry.visible) "v" else "h"}"
    }

    companion object {
        val Default = PlayerToolbarLayout(
            listOf(
                PlayerToolbarButton.NEXT_EPISODE,
                PlayerToolbarButton.SPEED,
                PlayerToolbarButton.RESIZE,
                PlayerToolbarButton.SUBTITLES,
                PlayerToolbarButton.CHAPTERS,
            ).map { PlayerToolbarEntry(it, visible = true) } +
                listOf(
                    PlayerToolbarButton.AUDIO,
                    PlayerToolbarButton.QUALITY,
                    PlayerToolbarButton.SOURCES,
                    PlayerToolbarButton.EPISODES,
                    PlayerToolbarButton.RATE,
                    PlayerToolbarButton.EXTERNAL_PLAYER,
                    PlayerToolbarButton.PLAYBACK_INFO,
                    PlayerToolbarButton.SWITCH_ENGINE,
                    PlayerToolbarButton.VIDEO_SETTINGS,
                    PlayerToolbarButton.SUBMIT_INTRO,
                ).map { PlayerToolbarEntry(it, visible = false) },
        )

        fun decode(value: String?): PlayerToolbarLayout {
            if (value.isNullOrBlank()) return Default
            val parsed = value.split(',').mapNotNull { token ->
                val button = PlayerToolbarButton.fromStorageId(token.substringBefore(':').trim())
                    ?: return@mapNotNull null
                PlayerToolbarEntry(button, visible = token.substringAfter(':', "v").trim() != "h")
            }.distinctBy { it.button }
            val missing = Default.entries.filter { default -> parsed.none { it.button == default.button } }
            return PlayerToolbarLayout(parsed + missing)
        }
    }
}
