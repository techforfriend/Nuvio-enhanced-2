package com.nuvio.app.features.player

/**
 * Customisable layout for the default player's bottom actions pill, ported
 * from Luqman's "Player buttons" option (Settings -> Playback -> Player).
 *
 * Default player only: the legacy player's header buttons are hard-coded and
 * do not read this layout, exactly like Luqman, where the option is hidden
 * while the legacy layout is on.
 *
 * The fork's button set is wider than Luqman's: Watch Party, PiP, Shuffle,
 * Orientation and Cast were added by the fork's bottom-pill work and are
 * included here, after the shared buttons' order is preserved in the default.
 * The default mirrors the fork's current pill: the old primary pool is
 * visible on the bar, the old secondary pool sits behind the arrow.
 */
enum class PlayerToolbarButton(val storageId: String) {
    WATCH_PARTY("watch_party"),
    PICTURE_IN_PICTURE("picture_in_picture"),
    QUALITY("quality"),
    SUBTITLES("subtitles"),
    AUDIO("audio"),
    NEXT_EPISODE("next_episode"),
    SPEED("speed"),
    RESIZE("resize"),
    CHAPTERS("chapters"),
    SWITCH_ENGINE("switch_engine"),
    SHUFFLE("shuffle"),
    ORIENTATION("orientation"),
    CAST("cast"),
    SOURCES("sources"),
    EPISODES("episodes"),
    RATE("rate"),
    EXTERNAL_PLAYER("external_player"),
    PLAYBACK_INFO("playback_info"),
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
                PlayerToolbarButton.WATCH_PARTY,
                PlayerToolbarButton.PICTURE_IN_PICTURE,
                PlayerToolbarButton.QUALITY,
                PlayerToolbarButton.SUBTITLES,
                PlayerToolbarButton.AUDIO,
                PlayerToolbarButton.NEXT_EPISODE,
                PlayerToolbarButton.SPEED,
                PlayerToolbarButton.RESIZE,
                PlayerToolbarButton.CHAPTERS,
                PlayerToolbarButton.SWITCH_ENGINE,
                PlayerToolbarButton.SHUFFLE,
                PlayerToolbarButton.ORIENTATION,
                PlayerToolbarButton.CAST,
            ).map { PlayerToolbarEntry(it, visible = true) } +
                listOf(
                    PlayerToolbarButton.SOURCES,
                    PlayerToolbarButton.EPISODES,
                    PlayerToolbarButton.RATE,
                    PlayerToolbarButton.EXTERNAL_PLAYER,
                    PlayerToolbarButton.PLAYBACK_INFO,
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
