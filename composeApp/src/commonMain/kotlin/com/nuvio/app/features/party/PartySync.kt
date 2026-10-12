package com.nuvio.app.features.party

import kotlin.math.abs

internal sealed interface PartyCorrection {
    data object InSync : PartyCorrection
    data class Seek(val positionMs: Long) : PartyCorrection
    data class Nudge(val rate: Float) : PartyCorrection
}

internal object PartySync {
    const val NUDGE_START_MS = 250L
    const val NUDGE_STOP_MS = 80L
    const val SEEK_THRESHOLD_MS = 1_500L
    const val PAUSED_TOLERANCE_MS = 500L
    const val NUDGE_FACTOR = 0.05f

    fun expectedPositionMs(playback: PartyPlayback, nowMs: Long, durationMs: Long = 0L): Long {
        val position = if (playback.paused) {
            playback.positionMs
        } else {
            val elapsed = (nowMs - playback.at).coerceAtLeast(0L)
            playback.positionMs + (elapsed * playback.rate).toLong()
        }
        val clamped = position.coerceAtLeast(0L)
        return if (durationMs > 0L) clamped.coerceAtMost(durationMs) else clamped
    }

    fun isNewer(candidate: PartyPlayback, current: PartyPlayback?): Boolean {
        if (current == null) return true
        if (candidate.seq != current.seq) return candidate.seq > current.seq
        return candidate.memberId.orEmpty() > current.memberId.orEmpty()
    }

    fun correction(
        localPositionMs: Long,
        expectedPositionMs: Long,
        playback: PartyPlayback,
        nudging: Boolean,
    ): PartyCorrection {
        val drift = localPositionMs - expectedPositionMs
        val distance = abs(drift)
        if (playback.paused) {
            return if (distance > PAUSED_TOLERANCE_MS) PartyCorrection.Seek(expectedPositionMs) else PartyCorrection.InSync
        }
        val threshold = if (nudging) NUDGE_STOP_MS else NUDGE_START_MS
        return when {
            distance > SEEK_THRESHOLD_MS -> PartyCorrection.Seek(expectedPositionMs)
            distance > threshold -> {
                val factor = if (drift > 0L) 1f - NUDGE_FACTOR else 1f + NUDGE_FACTOR
                PartyCorrection.Nudge(playback.rate * factor)
            }
            else -> PartyCorrection.InSync
        }
    }

    fun coordinatorId(party: ActiveParty): String? {
        val online = party.presence.keys + party.memberId
        val host = party.hostMemberId
        if (host != null && host in online) return host
        return party.members.firstOrNull { it.id in online }?.id ?: party.memberId
    }

    fun waitingMemberIds(party: ActiveParty, ignored: Set<String>): List<String> =
        party.presence.values
            .filter { it.mediaKey == party.media.key }
            .filter { it.status == PartyMemberStatus.Loading || it.status == PartyMemberStatus.Buffering }
            .map { it.memberId }
            .filterNot { it in ignored }
}
