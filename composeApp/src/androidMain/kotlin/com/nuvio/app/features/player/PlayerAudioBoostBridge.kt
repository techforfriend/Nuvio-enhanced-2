package com.nuvio.app.features.player

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.settings.NuvioEnhancedSettingsRepository

@OptIn(UnstableApi::class)
internal object PlayerAudioBoostBridge {
    private const val TAG = "PlayerAudioBoost"

    @Volatile
    private var boostFraction: Float = 0f

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentAudioSessionId: Int = 0
    private var mpvVolumeSetter: ((Double) -> Unit)? = null

    fun isEnabled(): Boolean {
        return NuvioEnhancedSettingsRepository.uiState.value.volumeBoostEnabled
    }

    fun getMaxBoostFraction(): Float {
        val maxPercent = NuvioEnhancedSettingsRepository.uiState.value.maxVolumeBoostPercent
        return ((maxPercent - 100).coerceIn(0, 100) / 100f)
    }

    fun getBoostFraction(): Float = boostFraction

    fun setBoostFraction(fraction: Float) {
        val clamped = if (isEnabled()) fraction.coerceIn(0f, getMaxBoostFraction()) else 0f
        boostFraction = clamped
        applyExoGain()
        applyMpvVolume()
    }

    fun attachExoPlayer(player: ExoPlayer): () -> Unit {
        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                updateAudioSessionId(audioSessionId)
            }
        }
        player.addListener(listener)
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId > 0) {
            updateAudioSessionId(player.audioSessionId)
        }

        return {
            player.removeListener(listener)
            releaseExo()
        }
    }

    private fun updateAudioSessionId(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        currentAudioSessionId = audioSessionId
        applyExoGain()
    }

    private fun applyExoGain() {
        val fraction = boostFraction
        val sessionId = currentAudioSessionId
        if (sessionId <= 0) return

        runCatching {
            if (fraction <= 0.001f) {
                loudnessEnhancer?.enabled = false
            } else {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(sessionId)
                }
                val enhancer = loudnessEnhancer ?: return@runCatching
                // 100% boost (200% volume total) maps to +12 dB (1200 mB)
                val targetGainMb = (fraction * 1200f).toInt().coerceIn(0, 1500)
                enhancer.setTargetGain(targetGainMb)
                enhancer.enabled = true
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to apply LoudnessEnhancer gain: ${e.message}")
        }
    }

    private fun releaseExo() {
        runCatching {
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null
        currentAudioSessionId = 0
        boostFraction = 0f
    }

    fun attachLibmpv(setter: (Double) -> Unit): () -> Unit {
        mpvVolumeSetter = setter
        applyMpvVolume()
        return {
            mpvVolumeSetter = null
            boostFraction = 0f
        }
    }

    private fun applyMpvVolume() {
        val setter = mpvVolumeSetter ?: return
        val targetMpvVolume = (1.0 + boostFraction.toDouble()) * 100.0
        setter(targetMpvVolume)
    }

    fun reset() {
        boostFraction = 0f
        releaseExo()
        mpvVolumeSetter = null
    }
}
