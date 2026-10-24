package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HeroTrailerSurface {
    Home,
    Details,
}

object HeroTrailerAudioState {
    private val states = HeroTrailerSurface.entries.associateWith { MutableStateFlow(true) }

    fun muted(surface: HeroTrailerSurface): StateFlow<Boolean> = state(surface).asStateFlow()

    fun toggleMuted(surface: HeroTrailerSurface) {
        val state = state(surface)
        state.value = !state.value
    }

    fun applyStartMuted(surface: HeroTrailerSurface, muted: Boolean) {
        state(surface).value = muted
    }

    private fun state(surface: HeroTrailerSurface): MutableStateFlow<Boolean> =
        states.getValue(surface)
}
